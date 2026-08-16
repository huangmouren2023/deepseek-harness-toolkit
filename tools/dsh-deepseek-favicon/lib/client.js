/**
 * dsh-deepseek-favicon — browser side.
 *
 * Replaces the tab favicon of the DSH web app with the official DeepSeek
 * whale logo. The logo is embedded as a base64 PNG data URI so the plugin is
 * self-contained (no host route, no external network, no source changes).
 *
 * Pure DOM: swaps `link[rel="icon"]` once the document head is available.
 */

window.__ModuleLoader__.load({
  id: '@dsh-external/dsh-deepseek-favicon',
  factory: (require) => {
    var module = { exports: {} }
    var exports = module.exports

    // Official DeepSeek favicon (www.deepseek.com/favicon.ico), 64x64 PNG.
    var FAVICON_DATA_URI =
      'data:image/png;base64,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'

    /** Replace the favicon link; creates one if the head lacks it. */
    function swapFavicon() {
      var head = document.head
      if (!head) return false
      var link = head.querySelector('link[rel="icon"]')
      if (link) {
        link.type = 'image/png'
        link.href = FAVICON_DATA_URI
      } else {
        var el = document.createElement('link')
        el.rel = 'icon'
        el.type = 'image/png'
        el.href = FAVICON_DATA_URI
        head.appendChild(el)
      }
      return true
    }

    function apply() {
      var attempts = 0
      function mount() {
        if (swapFavicon()) return
        if (++attempts < 50) setTimeout(mount, 200)
      }
      mount()
    }

    exports.inject = []
    exports.apply = apply
    return module.exports
  },
})
