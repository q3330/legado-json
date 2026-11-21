# Legado 书源调试常见故障排查清单

## 1. 搜索返回空列表或 0 条结果

1. **检查搜索 URL 与请求方式**：用 `book_source_fetch_html` 直接请求展开关键词后的 URL，确认 `status` 为 `200` 且返回真实搜索结果页，而不是首页、错误页或 Cloudflare 验证页。
2. **检查编码**：若站内使用 GBK/GB2312 搜索且出现无结果或乱码，在 `searchUrl` 后加 `,{"charset":"GBK"}` 或使用 `{{java.encode(key, "gbk")}}`。
3. **检查 POST 表单**：很多老站搜索框是 `<form method="post">`，务必确认表单 action、字段名及隐含参数。
4. **验证列表规则**：把拿到的真实 HTML 传给 `book_source_eval_rule`（设 `list_rule` 与 `field_rules`），逐步定位是 `bookList` 没命中还是 `name` / `bookUrl` 为空被过滤。

## 2. 封面不显示或全部显示相同占位图

1. 查看 `book_source_fetch_html` 返回的 `lazyImageAttributes`。
2. 若出现 `data-original`、`data-src`、`data-lazy`，不要只写 `img@src`，改成 `img@data-original||img@data-src||img@src`。
3. 若地址以 `//` 开头，加上 `##^//##https://`；若仍为相对路径，确认传入了正确的 `baseUrl`（在 `book_source_eval_rule` 里 `coverUrl` 默认按 URL 字段自动解析相对路径）。

## 3. 遇到 403 / 503 / Cloudflare / SPA 白屏

1. 用 `book_source_webview_capture`（`mode="webview"`, `wait_ms=4000`）验证 WebView 能否拿到真实 DOM。
2. 若页面靠接口渲染，换用 `mode="capture_requests"`（`replay_response=true`），观察 `capturedRequests` 找到后台 XHR JSON 接口，直接以 API 写书源通常比 DOM 解析更稳。
3. 若需要用户手动通过人机验证：启用 `enabledCookieJar=true`，设置合理的 `loginUrl`，并在 `bookSourceComment` 中提示先打开登录页过一次验证。

## 4. 目录为空或只抓取第一页

1. 单章类站点（如单条视频）：不要写依赖 `<ul class="chapter">` 的列表规则，改用 `chapterList: "@js:[1]"` 并配合 `ruleBookInfo.tocUrl: "{{baseUrl}}"`。
2. 分页目录：`nextTocUrl` 不能是当前页自身 URL（会导致死循环）；`<select>` 翻页用 `select option:not([selected])@value`。
3. 严禁 JS 规则尾部返回 `null` 或 `undefined`，字符串字段无值时返回 `""`，列表无值返回 `[]`。

## 5. 视频或正文调试失败（`state == -1`）

1. 检查 `book_source_debug` 日志最后一步失败位置：
   - 若停在搜索/详情/目录，优先检查该步 HTML 与 `book_source_eval_rule` 输出；
   - 若正文已提取出结果但仍报错 `state == -1`，对于 `bookSourceType = 4` 说明提取结果不是可达的有效视频直链（常见原因：漏了反转义 `\/` 或 `&amp;`、拿到 `.html` 页面地址、拿到了 `blob:` 地址、或目标 CDN 需要特定 `Referer` / `User-Agent`）。
2. 对于动态播放器，用 `book_source_webview_capture`（`mode="webview"` + `source_regex` 或 `mode="capture_requests"`）抓取实际请求流，确认返回状态和 `Content-Type`。

## 6. 保存前最终核对

- `book_source_debug` 使用最新 `source_override` 跑通搜索（或发现）-> 详情 -> 目录 -> 正文/播放探测，`success == true` 且无关键 stage 失败；
- 书源名称、域名 `bookSourceUrl`（作为主键）、`bookSourceType` 正确，注释 `bookSourceComment` 清晰说明特点与限制；
- 若有 `ruleExplore`，确认与 `exploreUrl` 的字段结构匹配。
