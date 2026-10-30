# Vendored libraries

Pinned copies so the PI tool runs from the folder alone (`file://`, offline,
no CDN). The page's Content-Security-Policy refuses scripts from anywhere
else. Each file is unmodified from the npm tarball, which was checked against
the registry's `dist.integrity` before extraction.

| File | Package | npm tarball integrity | SHA-256 of the file |
|---|---|---|---|
| `chart-4.4.1.umd.js` | chart.js 4.4.1, `dist/chart.umd.js` | `sha512-C74QN1bxwV1v2PEujhmKjOZ7iUM4w6BWs23Md/6aOZZSlwMzeCIDGuZay++rBgChYru7/+QFeoQW0fQoP534Dg==` | `74401d738dd3e03ee5dfb3b6841210fe2c4ead8a960c4011ca4ba0b78a9fd8f3` |
| `hammer-2.0.8.min.js` | hammerjs 2.0.8, `hammer.min.js` | `sha512-tSQXBXS/MWQOn/RKckawJ61vvsDpCom87JgxiYdGwHdOa0ht0vzUWDlfioofFCRU0L+6NGDt6XzbgoJvZkMeRQ==` | `7953631f0e54794d2352a3cfa591c0914d73e14f90141058e3cf16bee7939bcf` |
| `chartjs-plugin-zoom-2.0.1.min.js` | chartjs-plugin-zoom 2.0.1, `dist/chartjs-plugin-zoom.min.js` | `sha512-ogOmLu6e+Q7E1XWOCOz9YwybMslz9qNfGV2a+qjfmqJYpsw5ZMoRHZBUyW+NGhkpQ5PwwPA/+rikHpBZb7PZuA==` | `503c709802be285c676afe0369bf5f72066d0b0c23929188c3158f35c0327a9c` |

All MIT (`LICENSE-*.txt`). To upgrade: download the tarball from
`https://registry.npmjs.org/<pkg>/-/<pkg>-<version>.tgz`, compare its
SHA-512 with `dist.integrity` from `https://registry.npmjs.org/<pkg>/<version>`,
copy the same file, update the `<script>` tag and this table.
