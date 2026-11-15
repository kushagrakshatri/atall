import { afterAll, beforeEach, describe, it } from "@std/testing/bdd";
import { expect } from "@std/expect";
import { admin, connected, structured, freshEnv } from "./helpers.ts";
import { closePg } from "./pg.ts";

const OPTS = { sanitizeOps: false, sanitizeResources: false };

describe({ name: "tools", ...OPTS }, () => {
beforeEach(async () => {
  await freshEnv();
});
afterAll(async () => {
  await closePg();
});

describe("C0 tools end to end", () => {
  it("read the seeded task, post a result, and see it on the admin side (the kDot write test)", async () => {
    const kdot = await connected("kdot");

    const inbox = await kdot.callTool("atall_inbox");
    expect(inbox.status).toBe(200);
    const tasks = structured(inbox).tasks;
    const seed = tasks.find((t: any) => t.ref === "c0ck");
    expect(seed).toMatchObject({ task_id: "tsk_c0check", my_state: "SUBMITTED" });
    expect(inbox.body.result.content[0].text).toContain("#c0ck");

    const card = await kdot.callTool("atall_get_task", { task_id: "#c0ck" });
    expect(structured(card).goal).toContain("atall_post_result");
    expect(structured(card).verified).toBe(false);

    const post = await kdot.callTool("atall_post_result", {
      task_id: "c0ck",
      status: "done",
      summary: "I am kDot. Connector works.",
      claims: [{ text: "The MCP 2026-07-28 spec is stateless.", kind: "fact", confidence: 0.9, sources: [{ url: "https://blog.modelcontextprotocol.io/posts/2026-07-28/" }] }],
    });
    expect(post.status).toBe(200);
    expect(post.body.result.isError).toBeUndefined();
    expect(structured(post)).toMatchObject({ task_id: "tsk_c0check", from_agent: "kdot", replaced_previous: false });

    // answered tasks drop out of the inbox unless asked for
    expect(structured(await kdot.callTool("atall_inbox")).tasks.find((t: any) => t.ref === "c0ck")).toBeUndefined();
    const all = structured(await kdot.callTool("atall_inbox", { include_done: true })).tasks;
    expect(all.find((t: any) => t.ref === "c0ck").my_state).toBe("DONE");

    const ping = await kdot.callTool("atall_ping_write", { note: "write test" });
    expect(structured(ping).from_agent).toBe("kdot");

    const st = (await admin().state()).body;
    const t = st.tasks.find((x: any) => x.task_id === "tsk_c0check");
    expect(t.results).toHaveLength(1);
    expect(t.results[0]).toMatchObject({ agent_id: "kdot", status: "done", summary: "I am kDot. Connector works.", client_name: "ChatGPT" });
    expect(t.results[0].claims[0].sources[0].url).toContain("modelcontextprotocol");
    expect(st.pings[0]).toMatchObject({ agent_id: "kdot", note: "write test" });
  });

  it("the agent id comes from the token, never from the arguments", async () => {
    const grok = await connected("grok", "Grok");
    const r = await grok.callTool("atall_ping_write", { note: "hi", agent_id: "kdot" } as any);
    expect(r.body.result.isError).toBe(true);
    expect(r.body.result.content[0].text).toContain("unknown argument");
    const ok = await grok.callTool("atall_ping_write", {});
    expect(structured(ok).from_agent).toBe("grok");
  });

  it("routes a task to one agent: others can't see or answer it", async () => {
    const created = await admin().createTask({ title: "Grok only", goal: "Find one fact.", to: "grok" });
    expect(created.status).toBe(201);
    const ref = created.body.ref;
    const kdot = await connected("kdot");
    const grok = await connected("grok", "Grok");

    expect(structured(await kdot.callTool("atall_inbox")).tasks.find((t: any) => t.ref === ref)).toBeUndefined();
    const peek = await kdot.callTool("atall_get_task", { task_id: ref });
    expect(peek.body.result.isError).toBe(true);
    const sneak = await kdot.callTool("atall_post_result", { task_id: ref, status: "done", summary: "not mine" });
    expect(sneak.body.result.isError).toBe(true);

    expect(structured(await grok.callTool("atall_inbox")).tasks.find((t: any) => t.ref === ref)).toBeTruthy();
    await grok.callTool("atall_post_result", { task_id: created.body.task_id, status: "partial", summary: "half" });
    const again = await grok.callTool("atall_post_result", { task_id: created.body.task_id, status: "done", summary: "full" });
    expect(structured(again).replaced_previous).toBe(true);

    const st = (await admin().state()).body.tasks.find((t: any) => t.ref === ref);
    expect(st.state).toBe("DONE");
    expect(st.results).toHaveLength(1);
    expect(st.results[0].summary).toBe("full");
  });

  it("declined maps to REJECTED", async () => {
    const created = await admin().createTask({ title: "Duet task", goal: "Do a thing.", to: ["duet"] });
    const duet = await connected("duet", "Claude");
    await duet.callTool("atall_post_result", { task_id: created.body.task_id, status: "declined", summary: "Can't do that." });
    const all = structured(await duet.callTool("atall_inbox", { include_done: true })).tasks;
    expect(all.find((t: any) => t.task_id === created.body.task_id).my_state).toBe("REJECTED");
  });

  it("validates tool input and reports problems as tool errors", async () => {
    const kdot = await connected("kdot");
    const cases: [string, Record<string, unknown>, RegExp][] = [
      ["atall_post_result", { task_id: "c0ck", status: "finished", summary: "x" }, /status/],
      ["atall_post_result", { task_id: "c0ck", status: "done" }, /summary is required/],
      ["atall_post_result", { task_id: "c0ck", status: "done", summary: "x", claims: [{ text: "a", confidence: 2 }] }, /confidence/],
      ["atall_post_result", { task_id: "c0ck", status: "done", summary: "x", claims: [{ text: "a", sources: [{ url: "javascript:alert(1)" }] }] }, /http/],
      ["atall_post_result", { task_id: "nope", status: "done", summary: "x" }, /No Atall task/],
      ["atall_get_task", {}, /task_id is required/],
      ["atall_ping_write", { note: "x".repeat(201) }, /longer than 200/],
      ["atall_inbox", { include_done: "yes" }, /boolean/],
    ];
    for (const [tool, args, re] of cases) {
      const r = await kdot.callTool(tool, args);
      expect(r.status, tool).toBe(200);
      expect(r.body.result.isError, JSON.stringify(args)).toBe(true);
      expect(r.body.result.content[0].text).toMatch(re);
    }
  });

  it("unknown tools are a JSON-RPC invalid-params error", async () => {
    const kdot = await connected("kdot");
    const r = await kdot.callTool("atall_delete_everything", {});
    expect(r.body.error.code).toBe(-32602);
  });

  it("admin task creation validates input", async () => {
    expect((await admin().createTask({ title: "", goal: "x" })).status).toBe(400);
    expect((await admin().createTask({ title: "x", goal: "y", to: "Bad Agent!" })).status).toBe(400);
  });
});
});
