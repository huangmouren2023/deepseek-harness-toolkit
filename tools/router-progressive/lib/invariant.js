//#region lib/types/invariant.js
/**
* Package-owned invariant companion for `@deepseek-ai/dsh-router-progressive`.
* @module @deepseek-ai/dsh-router-progressive/invariant
*/
const PACKAGE_NAME = "@deepseek-ai/dsh-router-progressive";
/** Cordis companion plugin name. */
const name = "router-progressive-invariant";
/** Service required before the companion can reserve package ownership. */
const inject = ["invariants"];
/**
* No runtime invariant: router decisions are pure functions over durable
* session state; the package owns no independent event stream or mutable
* durable projection to check.
*/
const install = () => {};
/** Register this package's invariant companion. */
const apply = (ctx) => Promise.resolve(ctx.invariants.register(PACKAGE_NAME, install));
//#endregion
export { apply, inject, name };

