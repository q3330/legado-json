# Legado 文本、漫画、音频与文件书源模板

> 本模板对应 `BookSource` 实体核心字段：`bookSourceType` 取值分别为 `0`（文本小说）、`1`（音频/有声书）、`2`（图片/漫画）、`3`（下载文件）、`4`（视频，见视频专题）。

## 1. 标准字段结构骨架

```json
[
  {
    "bookSourceName": "站点名称",
    "bookSourceGroup": "分类标签",
    "bookSourceType": 0,
    "bookSourceUrl": "https://example.com",
    "enabled": true,
    "enabledExplore": true,
    "enabledCookieJar": true,
    "header": "{\"User-Agent\":\"Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/128.0.0.0 Mobile Safari/537.36\"}",
    "searchUrl": "https://example.com/search?q={{key}}&page={{page}}",
    "exploreUrl": "",
    "ruleSearch": {
      "checkKeyWord": "测试关键词",
      "bookList": "",
      "name": "",
      "author": "",
      "kind": "",
      "intro": "",
      "coverUrl": "",
      "bookUrl": "",
      "lastChapter": ""
    },
    "ruleExplore": {},
    "ruleBookInfo": {
      "init": "",
      "name": "",
      "author": "",
      "kind": "",
      "intro": "",
      "coverUrl": "",
      "lastChapter": "",
      "tocUrl": ""
    },
    "ruleToc": {
      "chapterList": "",
      "chapterName": "",
      "chapterUrl": "",
      "nextTocUrl": ""
    },
    "ruleContent": {
      "content": "",
      "nextContentUrl": "",
      "replaceRegex": ""
    }
  }
]
```

说明：当发现页的 HTML/JSON 结构与搜索页一致时，`ruleExplore` 留空 `{}` 即自动复用 `ruleSearch`；若结构不同，才单独填写 `ruleExplore`。

## 2. 文本小说（`bookSourceType: 0`）要点

- `ruleContent.content`：优先使用 `@textNodes` 或 `@html`，避免丢失段落换行。
- 正文分页：`nextContentUrl` 匹配“下一页”链接；如存在“下一章”和“下一页”共用同类选择器，必须限定文本或正则过滤，避免把下一章拼进同一章。**绝不使用**不存在的 `prevContentUrl`。
- 目录分页：`nextTocUrl` 可返回单个下一页链接，也可通过 `<js>` 或选择器返回**全部目录分页 URL 数组**实现并发抓取；`<select>` 翻页用 `select option:not([selected])@value`。
- 正文去杂：`replaceRegex` 删除章节内固定广告词、网址水印或多余标题。

## 3. 图片 / 漫画（`bookSourceType: 2`）要点

- `ruleContent.content` 需输出一组 `<img>` HTML 或图片地址列表，推荐使用 `<img src="...">` 或选择器提取后拼装：
```text
.comic-list img@data-original||.comic-list img@data-src||.comic-list img@src
<js>
String(result).split('\n').filter(Boolean).map(function(u){
  u = u.trim().replace(/^\/\//, 'https://');
  return '<img src="' + u + '">';
}).join('\n');
</js>
```
- 推荐设置 `ruleContent.imageStyle`: `"FULL"`，保证漫画大图全宽连续显示。
- 若章节图片需附带 Referer 防盗链，在 `header` 中配置好站点级 `Referer`。

## 4. 音频有声书（`bookSourceType: 1`）要点

- 静态音频直链：在 `ruleContent.content` 中提取 `.mp3` / `.m4a` / `.aac` / `.m3u8` URL（若在 JS 变量或 JSON 接口中，配合 `java.ajax()` 或正则提取）。
- 动态播放页嗅探：若网页通过前端 JS 生成签名音频地址，在 `ruleBookInfo.tocUrl` 或 `ruleToc.chapterUrl` 加上 `,{"webView":true}`，并配置 `ruleContent.sourceRegex` 为 `https?://[^"'\s]+\.(mp3|m4a|aac|m3u8)(\?[^"'\s]*)?`。

## 5. 下载文件源（`bookSourceType: 3`）要点

- 详情页可通过 `ruleBookInfo.downloadUrls` 提取网盘/压缩包直链；
- 若分文件在目录中呈现，则在 `ruleToc` 列出文件项并在 `ruleContent.content` 输出直接下载链接。
