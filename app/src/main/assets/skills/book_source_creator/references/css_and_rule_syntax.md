# Legado CSS 与通用规则语法参考

## 1. 规则模式前缀

| 模式 | 前缀/写法 | 说明 |
| --- | --- | --- |
| Default（CSS/Jsoup） | 省略前缀，或显式 `@css:` | 默认使用 Legado 扩展的 CSS 选择器；显式 `@css:` 使用标准 Jsoup CSS，不走 `@` 链式后缀拆分 |
| XPath | `@XPath:` 或 `//...` 开头 | 直接按 XPath 解析 HTML |
| JSONPath | `@Json:` 或 `$.` / `$[` 开头 | 解析 JSON 接口或已转换对象；推荐统一使用 `$.field` 样式 |
| 正则净化 | `##正则##替换值` 或 `##正则##替换值###` | 无 `###` 时替换所有匹配；末尾加 `###` 只保留第一个捕获/匹配并删除其余部分 |
| JavaScript | `<js>...</js>` 或 `@js:...` | 可放在选择器后面二次加工 `result`，或独立运行读取 `src` / `baseUrl` / `java` |
| 变量存取 | `@put:{k:规则}` / `@get:{k}` | 在同一次解析上下文中暂存并跨字段复用数据（如搜索页存标题供单章目录复用） |

## 2. Default 语法速查

### 元素选择与索引
- `tag` / `.class` / `#id` / `[attr]` / `[attr=val]` / `[attr*=val]` / `[attr^=val]` / `[attr$=val]`
- 链式层级使用 `@` 或空格：`div.list ul li@a`
- Legado 下标切片（写在选择器末尾，支持点号 `.` 或索引）：
  - `a.0@href`：第 1 个 `<a>` 的 `href`
  - `a.-1@href`：倒数第 1 个 `<a>`
  - `li!0`：排除第 1 个元素；`li!0:-1`：排除第 1 个和倒数第 1 个

### 内容与属性后缀（必须在 `@` 之后）
- `@text`：当前元素及其子元素的全部可见文本（正文常用 `textNodes` 保留换行结构）
- `@ownText`：仅当前元素自身直属文本，不含子标签文本（常用于去除角标/标签干扰）
- `@textNodes`：保留换行分隔的文本节点
- `@html`：内部 HTML（保留 `<img>` 等结构）
- `@all`：包含当前节点本身标签的外层 HTML
- `@href` / `@src` / `@data-original` / `@data-src` / `@data-lazy` / `@content` / `@alt` / `@value`：提取对应属性值

## 3. 组合运算符

- `规则1||规则2`：短路或（左为空或取不到值时才尝试右侧，如 `.img@data-src||.img@src`）
- `规则1&&规则2`：并集拼接（把两侧结果合并，常用于多段简介或正文+副文）
- `规则1%%规则2`：交叉合并（两列对齐交错合并，较少用）

## 4. 严禁踩坑的语法限制

1. **禁止 `select@value`**：`<select>` 标签本身没有动态当前值，需提取 `<option>`，例如：
   - 所有分页链接：`select option@value`
   - 仅非当前页：`select option:not([selected])@value`
2. **禁止捏造或依赖脆弱伪类**：不要写未实测的 jQuery 伪类（如 `:contains(...)`、`:eq(0)`）；也尽量避开 `:first-child`/`:last-child`，改用 Legado 下标 `.0`、`.-1` 或准确类名。
3. **禁止在 `ruleContent` 中写 `prevContentUrl`**：Legado 仅支持 `nextContentUrl` 与 `nextTocUrl`。
4. **懒加载图片过滤**：很多网站 `<img src="/static/loading.gif" data-original="https://...">`，务必把真实属性放前面：`img@data-original||img@data-src||img@src`，并用正则补全协议 `##^//##https://` 或过滤占位图。
5. **正文净化模板**：
   - 去广告文字/提示：`div#content@textNodes##(本章完|请收藏本站|最新网址：[^\\s]+)##`
   - 保留正文 `<p>` 换行尽量用 `@textNodes` 或 `@html`，再交给 Legado 正文解析器自动格式化。

## 5. JavaScript 内置对象常用 API

- `result`：上一步规则的输出（字符串、元素或数组）；
- `src`：当前页面原始源码文本；
- `baseUrl`：当前请求解析后的基地址；
- `java.ajax(url)`：同步发起 HTTP 请求并返回响应文本（适合补充请求 API 或嵌套 iframe）；
- `java.connect(url)`：返回连接响应详情；
- `java.encodeURI(str)` / `java.encode(str, "gbk")`：URL 编码；
- `java.aesDecodeToString(...)` / `java.md5Encode(...)` / `java.base64Decode(...)`：常用加解密；
- `cookie.getCookie(url)` / `cookie.setCookie(url, ck)` / `cookie.removeCookie(url)`：操作 Cookie。
