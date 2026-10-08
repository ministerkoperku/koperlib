// admin / gametest / net / editor. packs import these for dev tooling, a server never needs them.
// importing works, touching anything tells you straight away that koperlib does not have it
const no = (what) => () => { throw new Error("koperlib bedrock: " + what + " is not available here"); };
export const variables = { get: () => undefined, getAllVariableNames: () => [] };
export const secrets = { get: () => undefined, getAllSecretNames: () => [] };
export const http = { request: no("http.request"), get: no("http.get"), cancelAll() {} };
export const register = no("gametest.register");
export const registerAsync = no("gametest.registerAsync");
export class HttpRequest { constructor(uri) { this.uri = uri; } }
export class HttpHeader { constructor(k, v) { this.key = k; this.value = v; } }
export const HttpRequestMethod = Object.freeze({ Delete: "Delete", Get: "Get", Head: "Head", Post: "Post", Put: "Put" });
export default {};
