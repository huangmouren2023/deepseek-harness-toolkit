# dsh-deepseek-favicon

[![dsh-plugin](https://img.shields.io/badge/dsh--plugin-%E2%9C%93-5B4CF0?style=flat-square)](https://github.com/topics/dsh-plugin)

> 把 DeepSeek Harness 的标签页图标换成 DeepSeek 官方鲸鱼 logo。默认的鲸鱼娘头像在深色标签页下黑乎乎一片，换成官方 logo 清晰醒目——夺舍成功 😆

作者：小墨

## 这是什么

DSH 自带的 favicon 是「小墨/鲸鱼娘」头像（深蓝底 `#0b1026` + 头像 PNG），在深色浏览器标签页里几乎看不见。这个插件在**浏览器端**把 `<link rel="icon">` 替换成 DeepSeek 官方 favicon（来自 www.deepseek.com/favicon.ico 的 64×64 鲸鱼 PNG）。

纯 client 端实现：

- logo 以 **base64 data URI 内嵌**在 client.js 里，自包含、零 host 路由、零网络依赖；
- **完全不碰 DSH checkout 源码**——符合插件安装铁律，只通过 profile link 挂载；
- host 端 `lib/index.js` 是空 apply（只为 bundle 提供可加载入口）。

## 验证状态

✅ **本机验证完毕**：headless Chrome 实测，页面加载后 `link[rel="icon"]` 的 href 变为 `data:image/png;base64,...`，type 为 `image/png`；16×16 模拟标签页里蓝色鲸鱼清晰可辨、不糊、对比良好。

## 工作机制

1. `apply()` 轮询等待 `document.head` 就绪（最多 50 次 × 200ms）；
2. 找到 `link[rel="icon"]` 就把 `type` 改为 `image/png`、`href` 改为内嵌 data URI；
3. head 里没有该 link 时新建一个（防御性兜底）。

浏览器对 favicon 有缓存，替换后可能需要**强刷一次**（Ctrl+F5）才看到新图标。

## 安装

放在 `~/.dsh/dsh-external/dsh-deepseek-favicon/`，然后在 profile 里 link：

```jsonc
// ~/.dsh/profiles/web/package.json
{
  "dependencies": {
    "@dsh-external/dsh-deepseek-favicon": "link:C://Users//<你>//.dsh//dsh-external//dsh-deepseek-favicon"
  },
  "dsh": { "profile": { "bundles": [ "...", "@dsh-external/dsh-deepseek-favicon" ] } }
}
```

然后 `pnpm install` 并重启 DSH。

## 备注

- 图标源是 DeepSeek 官网 favicon（`https://www.deepseek.com/favicon.ico`），64×64 PNG 提取后转 base64 内嵌。品牌标识归 DeepSeek 所有，本插件仅为本地界面美化。
- 若想换回默认头像，从 profile 的 `bundles` 里移除 `@dsh-external/dsh-deepseek-favicon` 并重启即可。

## License

MIT
