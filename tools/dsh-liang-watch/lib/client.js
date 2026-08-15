/**
 * dsh-liang-watch — browser side.
 *
 * A "梁强度雷达" panel: a sidebar footer action that opens a card showing the
 * community's current liang strength score (stage, vote counts) and the daily
 * timeline, fetched through the host proxy route. Pure DOM + fetch.
 */

window.__ModuleLoader__.load({
  id: '@dsh-external/dsh-liang-watch',
  factory: (require) => {
    var module = { exports: {} }
    var exports = module.exports

    var PROXY = '/_dsh/liang'

    /** Fetch one proxy endpoint as JSON. */
    function proxyGet(path) {
      return fetch(PROXY + path, { credentials: 'same-origin' })
        .then(function (res) { return res.json() })
    }

    /** Stage → emoji + color hint for the card. */
    function stageStyle(stage) {
      var map = {
        '小难梁': { emoji: '🌱', color: '#6b7280' },
        '牢梁': { emoji: '🏳️', color: '#9ca3af' },
        '梁子': { emoji: '⚖️', color: '#4e7cff' },
        '梁圣': { emoji: '✨', color: '#a855f7' },
        '梁神': { emoji: '🌟', color: '#f59e0b' },
        '梁祖': { emoji: '👑', color: '#ef4444' },
      }
      return map[stage] ?? { emoji: '❓', color: '#888' }
    }

    /** Render the panel into the given anchor button. */
    function showPanel(anchor) {
      var panel = document.createElement('div')
      panel.id = 'dsh-liang-watch-panel'
      panel.style.cssText = [
        'position:fixed', 'z-index:9999', 'width:300px', 'padding:14px',
        'border-radius:12px',
        'background:var(--dsw-alias-bg-layer-3, #1b1b1d)',
        'border:1px solid var(--dsw-alias-border-l2, #333)',
        'box-shadow:0 12px 32px rgba(0,0,0,.4)',
        'font-size:13px', 'line-height:1.5',
        'color:var(--dsw-alias-label-primary, #eee)',
      ].join(';')

      var header = document.createElement('div')
      header.textContent = '梁强度雷达'
      header.style.cssText = 'font-weight:700;font-size:14px;margin-bottom:10px'
      panel.append(header)

      var status = document.createElement('div')
      status.textContent = '加载中…'
      status.style.cssText = 'color:var(--dsw-alias-label-caption, #999)'
      panel.append(status)

      document.body.append(panel)
      var rect = anchor.getBoundingClientRect()
      panel.style.left = Math.max(8, rect.left - panel.offsetWidth + rect.width) + 'px'
      panel.style.top = Math.max(8, rect.top - panel.offsetHeight - 4) + 'px'
      var close = function (event) {
        if (panel.contains(event.target)) return
        document.body.removeChild(panel)
        document.removeEventListener('mousedown', close, true)
      }
      setTimeout(function () { document.addEventListener('mousedown', close, true) }, 0)

      // Load score + timeline.
      Promise.all([proxyGet('/score'), proxyGet('/timeline')])
        .then(function (results) {
          var score = results[0]
          var timeline = results[1]
          status.remove()

          var st = stageStyle(score.stage)
          var scoreRow = document.createElement('div')
          scoreRow.style.cssText = 'display:flex;align-items:center;gap:10px;margin-bottom:10px'
          var emoji = document.createElement('span')
          emoji.textContent = st.emoji
          emoji.style.cssText = 'font-size:26px'
          var main = document.createElement('div')
          main.style.cssText = 'flex:1'
          var stageLine = document.createElement('div')
          stageLine.style.cssText = 'font-weight:600;font-size:15px;color:' + st.color
          stageLine.textContent = score.stage + '（' + (score.score >= 0 ? '+' : '') + score.score.toFixed(2) + '）'
          var votes = document.createElement('div')
          votes.style.cssText = 'color:var(--dsw-alias-label-caption, #999);font-size:12px'
          votes.textContent = score.voterCount + ' 人投票 · 今日 ' + score.todayVoterCount + ' · 正' + score.positiveCount + '/负' + score.negativeCount + '/中立' + score.neutralCount
          main.append(stageLine, votes)
          scoreRow.append(emoji, main)
          panel.append(scoreRow)

          // Vote quick buttons.
          var voteRow = document.createElement('div')
          voteRow.style.cssText = 'display:flex;gap:6px;margin-bottom:10px'
          ;[['👑 +15', 15], ['⚖️ 0', 0], ['🌱 -15', -15]].forEach(function (item) {
            var btn = document.createElement('button')
            btn.type = 'button'
            btn.textContent = item[0]
            btn.style.cssText = [
              'flex:1', 'padding:4px 0', 'border:1px solid var(--dsw-alias-border-l2, #444)',
              'border-radius:6px', 'background:transparent', 'color:inherit',
              'cursor:pointer', 'font-size:12px',
            ].join(';')
            btn.onclick = function () {
              btn.disabled = true
              btn.textContent = '…'
              fetch(PROXY + '/vote', {
                method: 'POST', credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ position: item[1] }),
              }).then(function (res) { return res.json() })
                .then(function (result) {
                  btn.textContent = result.accepted ? '✅ 已投' : ('已投过/冷却')
                  var tip = document.createElement('div')
                  tip.style.cssText = 'color:var(--dsw-alias-label-caption, #999);font-size:11px;margin-top:6px'
                  tip.textContent = result.accepted
                    ? '投票成功！当前 ' + result.stage + '（' + result.score.toFixed(2) + '）'
                    : (result.reason === 'cooldown' ? '3 小时冷却中，下次 ' + new Date(result.nextVoteAt).toLocaleTimeString() : '投票未接受：' + (result.reason ?? 'unknown'))
                  voteRow.append(tip)
                })
                .catch(function () { btn.textContent = '失败'; btn.disabled = false })
            }
            voteRow.append(btn)
          })
          panel.append(voteRow)

          // Timeline mini-list.
          if (Array.isArray(timeline) && timeline.length > 0) {
            var tlTitle = document.createElement('div')
            tlTitle.textContent = '每日快照'
            tlTitle.style.cssText = 'font-weight:600;font-size:12px;margin:8px 0 4px;color:var(--dsw-alias-label-caption, #999)'
            panel.append(tlTitle)
            timeline.slice(-7).forEach(function (day) {
              var row = document.createElement('div')
              row.style.cssText = 'display:flex;justify-content:space-between;font-size:12px;padding:2px 0'
              var left = document.createElement('span')
              left.textContent = day.date + ' ' + day.stage
              var right = document.createElement('span')
              right.style.cssText = 'color:var(--dsw-alias-label-caption, #999)'
              right.textContent = (day.score >= 0 ? '+' : '') + day.score.toFixed(1) + ' · ' + day.voterCount + '人'
              row.append(left, right)
              panel.append(row)
            })
          }
        })
        .catch(function (err) {
          status.textContent = '加载失败：' + (err && err.message ? err.message : String(err))
        })
    }

    /** Render the footer action button. */
    function renderAction(wide) {
      var button = document.createElement('button')
      button.type = 'button'
      button.title = '梁强度雷达'
      var padding = wide ? '0 12px' : '0'
      button.style.cssText = [
        'display:inline-flex', 'align-items:center', 'justify-content:center',
        'gap:6px', 'height:36px', 'padding:' + padding, 'min-width:36px',
        'border:none', 'border-radius:18px', 'background:transparent',
        'color:var(--dsw-alias-label-primary, inherit)', 'cursor:pointer', 'font:inherit',
      ].join(';')
      button.textContent = wide ? '👑 梁强度' : '👑'
      button.onclick = function () { showPanel(button) }
      return button
    }

    function apply(ctx) {
      var footArea = null
      var attempts = 0
      function mount() {
        var actions = document.querySelector('[class*="footerActions"]')
        if (actions) {
          if (footArea !== actions) {
            footArea = actions
            var wide = !document.querySelector('[class*="sidebar"][class*="collapsed"]')
            actions.append(renderAction(wide))
          }
          return
        }
        if (++attempts < 50) setTimeout(mount, 200)
      }
      mount()
      ctx.effect(function () {
        return function () {
          if (footArea && footArea.lastChild) footArea.removeChild(footArea.lastChild)
        }
      }, 'dsh-liang-watch: footer action')
    }

    exports.inject = []
    exports.apply = apply
    return module.exports
  },
})
