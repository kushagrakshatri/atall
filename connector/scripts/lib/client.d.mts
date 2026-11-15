// Loose types for client.mjs (shared by the Deno test suite and the Node scripts).
type FetchLike = (input: any, init?: any) => Promise<Response>;
export declare const MODERN: string;
export declare function pkcePair(): Promise<{ verifier: string; challenge: string }>;
export interface RpcOptions { modern?: boolean; headers?: Record<string, string | null>; token?: string | null; notification?: boolean }
export interface RpcResult { status: number; headers: Headers; body: any }
export interface AuthorizeOptions { ownerCode?: string; agentId?: string; agentOther?: string; decision?: string; clientId?: string; verifierOverride?: string }
export declare class AtallClient {
  constructor(opts: { base: string; fetch?: FetchLike; clientName?: string; redirectUri?: string });
  base: string; redirectUri: string; verifier: string; tokens: any; client: any; as: any; prm: any;
  discover(): Promise<{ status: number; wwwAuthenticate: string; prm: any; as: any; asUrl: string; asTried: { url: string; status: number }[] }>;
  register(extra?: Record<string, unknown>): Promise<any>;
  authorize(opts?: AuthorizeOptions): Promise<{ status: number; location?: string; params: Record<string, string>; html?: string; json?: any }>;
  consentUrl?: string; consentInfo?: any;
  tokenRequest(params: Record<string, string>): Promise<{ status: number; body: any }>;
  exchange(code: string): Promise<{ status: number; body: any }>;
  refresh(refreshToken?: string): Promise<{ status: number; body: any }>;
  connect(opts: { ownerCode: string; agentId: string; agentOther?: string }): Promise<any>;
  rpc(method: string, params?: Record<string, any>, opts?: RpcOptions): Promise<RpcResult>;
  callTool(name: string, args?: Record<string, unknown>, opts?: RpcOptions): Promise<RpcResult>;
}
export declare class AtallAdmin {
  constructor(opts: { base: string; token: string; fetch?: FetchLike });
  req(method: string, path: string, body?: unknown): Promise<{ status: number; body: any }>;
  state(): Promise<{ status: number; body: any }>;
  createTask(t: Record<string, unknown>): Promise<{ status: number; body: any }>;
  createClient(c: Record<string, unknown>): Promise<{ status: number; body: any }>;
  createHeaderToken(agentId: string, label?: string): Promise<{ status: number; body: any }>;
  revoke(connId: string): Promise<{ status: number; body: any }>;
  reset(): Promise<{ status: number; body: any }>;
}
