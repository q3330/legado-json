# Legado 请求构造、URL Options 与编码处理

## 1. searchUrl 与 exploreUrl 基础模板

### GET 搜索
- 标准写法：`https://example.com/search?q={{key}}&page={{page}}`
- 第一页省略页码、第 2 页起拼接：`/search/{{key}}<,/page/{{page}}/>` 或 `/search?q={{key}}<,&p={{page}}>`
- 空格转加号：`https://example.com/q/{{String(key).trim().replace(/\s+/g,'+')}}/<,{{page}}/>`
- UTF-8 显式编码：`https://example.com/search?q={{encodeURI(key)}}`

### POST 搜索
URL 后用 `,` 紧跟 JSON 选项对象：
```text
https://example.com/search.php,{"method":"POST","body":"keyword={{key}}&page={{page}}"}
```
提交 JSON Body：
```text
https://api.example.com/search,{"method":"POST","headers":{"Content-Type":"application/json"},"body":"{\"keyword\":\"{{key}}\",\"page\":{{page}}}"}
```

### 发现栏 exploreUrl 两种写法
1. **分组::URL 多行格式**（简洁直观）：
```text
全部::/list/all/<,{{page}}.html>
热门::/list/hot/<,{{page}}.html>
```
2. **Flex JSON 数组格式**（多列排版，推荐）：
```json
[
  {"title":"热门","url":"/hot/<,{{page}}/>","style":{"layout_flexGrow":1,"layout_flexBasisPercent":0.25}},
  {"title":"最新","url":"/new/<,{{page}}/>","style":{"layout_flexGrow":1,"layout_flexBasisPercent":0.25}}
]
```

## 2. URL Options 常用字段

语法：`原始URL,{"key":"value",...}`，支持动态拼接：`{{baseUrl}},{"webView":true}` 或规则里用 `##$##,{"webView":true}` 追加。

| 字段 | 类型 | 作用 |
| --- | --- | --- |
| `method` | string | `"GET"` / `"POST"` |
| `body` | string | POST 请求体 |
| `headers` | object | 请求头（如 `Referer`、`X-Requested-With`、`Authorization`） |
| `charset` | string | 指定请求参数编码与响应解码字符集，如 `"GBK"`、`"GB2312"`、`"BIG5"`、`"UTF-8"` |
| `webView` | boolean | `true` 时通过 Android WebView 渲染页面后再读取 DOM / 嗅探资源 |
| `js` | string | 配合 `webView:true`，页面加载后执行的 JS |
| `retry` | number | 失败重试次数 |
| `type` | string | 指定特殊内容类型（如 `"video"` 等，一般配合规则使用） |

## 3. 中文非 UTF-8 站点编码规范（GBK / GB2312 / BIG5）

当 `book_source_fetch_html` 返回的 `detectedCharset` 不是 `UTF-8` 时：
1. 在 `searchUrl`、`exploreUrl`（必要时包括详情/目录/正文 URL）选项中添加 `"charset":"GBK"`（或 `"GB2312"` / `"BIG5"`），这样 Legado 既会按该编码提交 `{{key}}`，也会按该编码解码 HTML。
2. 若 URL 路径段本身需要 GBK URL 编码：
```text
https://www.example.com/search.php?searchkey={{java.encode(key, 'gbk')}},{"charset":"gbk"}
```
3. 提交 GBK POST 表单同样只需附带 `"charset":"gbk"`，并在 `searchUrl` 里写明参数，无需手动两次编码。

## 4. 请求头、Cookie 与反爬 / 人机验证应对

1. **全局 `header` 字段**（字符串化 JSON）：
```json
"{\"User-Agent\":\"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36\",\"Referer\":\"https://example.com/\",\"Accept-Language\":\"en-US,en;q=0.9\"}"
```
2. **`enabledCookieJar`**：若站点依赖登录态、年龄确认 Cookie（如 `age_verified=1`）或 Cloudflare `cf_clearance`，务必设为 `true`。
3. **`concurrentRate`**：对容易 429/503 的站点设置请求间隔，如 `"1/1000"`（每秒最多 1 次）。
4. **遇 JS 跳转或 403/Cloudflare**：
   - 先用 `book_source_webview_capture`（`mode="webview"`）测试 WebView 能否正常渲染并获得内容；
   - 若需要用户手动过人机验证，可填写 `loginUrl`（如指向搜索页或首页），并在 `bookSourceComment` 说明需先点击登录过验证。
   - 如需清理旧 Cookie 重新挑战，可在规则/JS 中调用 `cookie.removeCookie(baseUrl)`。
