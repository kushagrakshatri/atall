# chatgpt-auth

Pure Kotlin/JVM "Sign in with ChatGPT" (OpenAI's open-source, locally hosted route) + streaming Responses client
with tool calling, for the new app's main agent. No Android imports; the Android glue (Custom Tab, Keystore key,
manifest) is in NOTES.md §3–4.

Build and test: `./gradlew :chatgpt-auth:test` (JVM only). Originally verified with a Gradle-free kotlinc harness (override with
JAVA_HOME / KOTLINC_HOME). Tests run only against a local mock on 127.0.0.1; nothing calls OpenAI.

```kotlin
val auth = ChatGptAuth(SiwcConfig(appName = "Atall"), store)          // one per process
LoopbackServer("Atall").use { lb ->
    val attempt = auth.beginSignIn(SignInMode.DEFAULT, lb.redirectUri); lb.start(attempt)
    openCustomTab(auth.authorizeUrl(attempt))
    (lb.await() as? Callback.Success)?.let { auth.completeSignIn(attempt, it) }
}
val client = ResponsesClient(auth.config, ChatGptAuth.defaultHttp(), auth)
val runner = AgentRunner(client, model = "gpt-6.1-sol", instructions = "...", tools = listOf(askAgent, wait, reply))
val result = runner.run(history)   // TEXT | FINAL_TOOL | SUSPENDED (resume later) | MODEL_CALL_LIMIT
```
See NOTES.md for the protocol, storage, refresh, limits, risks and open questions.
