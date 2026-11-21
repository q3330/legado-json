# Legado 视频书源专题模板（bookSourceType = 4）

`legado-json` 内置视频播放器依赖 `bookSourceType = 4`。调试时 `Debug.debugTypedContent` 会对解析出的内容 URL 执行连通性探测（`SourceContentProbe`），只有拿到**真实可读写的 HTTP/HTTPS 视频直链或播放列表（如 `.mp4`、`.m3u8`）** 才会成功；返回网页 HTML、`blob:` 地址或空字符串均视为失败（`state == -1`）。

## 1. 两类视频站点的目录结构写法

### 类型 A：单视频卡片站（无剧集列表，一页即一条视频）
如短视频、Tube 类站点：搜索页展示单个视频卡片，点进详情页就直接播放。
- `ruleBookInfo.tocUrl`：填 `{{baseUrl}}`（若播放提取需要 WebView，写 `{{baseUrl}}##$##,{"webView":true}`）
- `ruleToc`：
```json
"ruleToc": {
  "chapterList": "@js:[1]",
  "chapterName": "h1@text||@get:{t}"
}
```
说明：`chapterUrl` 留空时，Legado 自动使用 `tocUrl`（即视频页面本身）作为正文/播放抓取 URL；同时可在 `ruleSearch.name` 中用 `@put:{t:@@标题选择器@text}` 把片名传给目录名。

### 类型 B：多集剧集 / 番剧站（详情页 -> 分集列表 -> 播放页）
- `ruleBookInfo.tocUrl`：若分集列表就在详情页，可留空或写 `{{baseUrl}}`；
- `ruleToc.chapterList`：指向每个选集节点，`chapterName` 取分集名（如第01集），`chapterUrl` 取对应播放页链接（如需嗅探，在 `chapterUrl` 规则后用 `<js>result + ',{"webView":true}'</js>`）。

## 2. 视频直链提取的三级优先级

### 优先级 1：直取源码里的 `<video>` / `<source>` 或内联 JS 配置（最快、最稳）
先用 `book_source_fetch_html` 查看播放页源码，常见结构有：
1. 标准 HTML5 标签：`#player source.-1@src||video source@src||video@src`
2. 播放器脚本调用：如 `html5player.setVideoUrlHigh('https://...')`、`html5player.setVideoHLS('https://...')`、`player_aaaa={"url":"https:\/\/..."}`、JSON-LD `"contentUrl": "https://..."`。
示例（`ruleContent.content`）：
```text
video source.-1@src||video source@src||video@src
<js>
var u = String(result || '').trim().replace(/&amp;/g, '&');
if (!u) {
  var html = String(src || '');
  var m = html.match(/setVideoUrlHigh\(\s*['"]([^'"]+)['"]\s*\)/i)
       || html.match(/setVideoHLS\(\s*['"]([^'"]+)['"]\s*\)/i)
       || html.match(/https?:\/\/[^"'\s\\]+\.(?:m3u8|mp4)(?:\?[^"'\s\\]*)?/i);
  if (m) u = (m[1] || m[0]).replace(/\\\//g, '/').replace(/&amp;/g, '&');
}
u;
</js>
```

### 优先级 2：接口或内嵌 iframe 嵌套请求
如果播放页只放了一个 `<iframe src="https://embed.example/...">` 或 JS 请求某个 JSON 接口：
- 用 `book_source_webview_capture`（`mode="capture_requests"`）抓取播放页加载的所有请求，定位返回视频 URL 的 API 或 iframe；
- 若逻辑简单，可在 `ruleContent.content` 中用 `<js>` 提取 iframe/参数并调用 `java.ajax(apiUrl)` 拿到 `.m3u8`/`.mp4` 直链返回。

### 优先级 3：WebView 渲染 + `sourceRegex` 网络嗅探
遇到链接加密混淆、前端 JS 解密或跳转外站播放时：
1. 在正文请求 URL 启用 WebView（例如 `ruleBookInfo.tocUrl` 或 `chapterUrl` 拼上 `,{"webView":true}`）；
2. 在 `ruleContent` 配置 `sourceRegex`：
```json
"ruleContent": {
  "content": "",
  "webJs": "",
  "sourceRegex": "https?://[^\"'\\s]+\\.(m3u8|mp4)(\\?[^\"'\\s]*)?"
}
```
3. 可先用 `book_source_webview_capture`（`mode="webview"`, `source_regex=...`, 必要时加 `js` 自动点击播放按钮）在设备上实测能否嗅探出有效 `sniffedUrls` 并通过外链探测。
4. 注意：许多 CDN 视频有防盗链，务必检查 `header` 是否正确带上了 `Referer` 和 `User-Agent`；对含 `&amp;` 或 `\/` 的地址记得先反转义。
