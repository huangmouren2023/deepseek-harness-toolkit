/**
 * dsh-deepseek-favicon — host side.
 *
 * Client-only plugin: the browser bundle replaces the tab favicon with the
 * official DeepSeek whale logo (embedded as a data URI in client.js). The host
 * side exists only so the bundle has a loadable entry; it performs no work.
 */

export async function apply() {
  // No host behavior needed: the favicon swap happens entirely in the browser.
}
