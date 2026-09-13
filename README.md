# CloudStream Proxy Extension

A configurable CloudStream 3 provider for **websites you own, operate, or are authorized to access**.

## Features

- Add multiple website definitions through `sites.json`.
- Optional per-site search URL using `{query}` and `{page}` placeholders.
- CSS selectors for search results, titles, posters, descriptions, and media.
- Detect direct `.mp4`, `.webm`, and `.m3u8` media.
- Pass iframe URLs to CloudStream extractors when supported.
- Optional per-site proxy template using a `{url}` placeholder.
- Dark-pink project theme in `theme.json`.
- GitHub Actions builds a `.cs3` extension artifact.

## Add a website

Edit `sites.json` and add a `SiteConfig` object:

```json
{
  "name": "My Website",
  "baseUrl": "https://example.com",
  "enabled": true,
  "searchUrl": "https://example.com/search?q={query}&page={page}",
  "resultSelector": ".card",
  "linkSelector": "a.card-link",
  "titleSelector": ".title",
  "posterSelector": "img",
  "descriptionSelector": ".description",
  "mediaSelector": "video source, video, iframe",
  "proxy": null
}
```

Selectors are site-specific. The generic provider cannot reliably scrape every website automatically.

## Add an authorized proxy

Add a proxy only when you control it or have permission to use it:

```json
{
  "name": "My Authorized Proxy",
  "template": "https://proxy.example/?url={url}",
  "enabled": true
}
```

Then set the site's `proxy` field to `"My Authorized Proxy"`.

The extension does **not** implement DRM circumvention, authentication bypass, CAPTCHA bypass, or access-control evasion.

## Build

Push to `main` or run the **Build CloudStream Extension** workflow manually. The resulting `.cs3` file is uploaded as a GitHub Actions artifact.

CloudStream extension builds follow the current CloudStream extension architecture and use the standard `MainAPI`/`BasePlugin` registration model.
