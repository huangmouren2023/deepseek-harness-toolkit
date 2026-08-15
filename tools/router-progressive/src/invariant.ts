/**
 * Package-owned invariant companion for `@deepseek-ai/dsh-router-progressive`.
 * @module @deepseek-ai/dsh-router-progressive/invariant
 */

import type { Context } from '@deepseek-ai/cordis'
import type { InvariantInstaller } from '@deepseek-ai/dsh-invariants'

const PACKAGE_NAME = '@deepseek-ai/dsh-router-progressive'

/** Cordis companion plugin name. */
export const name = 'router-progressive-invariant'

/** Service required before the companion can reserve package ownership. */
export const inject = ['invariants']

/**
 * No runtime invariant: router decisions are pure functions over durable
 * session state; the package owns no independent event stream or mutable
 * durable projection to check.
 */
const install: InvariantInstaller = () => {}

/** Register this package's invariant companion. */
export const apply = (ctx: Context): Promise<() => void> =>
  Promise.resolve(ctx.invariants.register(PACKAGE_NAME, install))

