# Third-party notices

frakworks is licensed under the MIT License (`LICENSE`). It ships with or
depends at runtime on the third-party software below, each under its own
license. Test- and build-only dependencies (JUnit, ArchUnit, Electron's
builder, esbuild and the like) are not listed.

## Engine (`engine/`, `sdk/`)

| Component | License |
|---|---|
| Spring Boot (webmvc, jdbc, flyway starters) | Apache-2.0 |
| Operaton BPM Spring Boot starter | Apache-2.0 |
| Flyway (incl. flyway-database-postgresql) | Apache-2.0 |
| Jackson (incl. jackson-module-kotlin) | Apache-2.0 |
| Kotlin standard library, kotlin-reflect | Apache-2.0 |
| docker-java (core, transport-zerodep) | Apache-2.0 |
| Fabric8 Kubernetes client | Apache-2.0 |
| networknt json-schema-validator | Apache-2.0 |
| PostgreSQL JDBC driver | BSD-2-Clause |
| H2 Database | MPL-2.0 or EPL-1.0 (used under MPL-2.0) |

## Desktop app (`observability/desktop/`), runtime dependencies

| Package | Version | License |
|---|---|---|
| @bpmn-io/diagram-js-ui | 0.2.4 | MIT |
| @types/trusted-types | 2.0.7 | MIT |
| argparse | 2.0.1 | Python-2.0 |
| bpmn-js | 18.30.1 | bpmn.io License (below) |
| bpmn-moddle | 10.3.1 | MIT |
| builder-util-runtime | 9.7.0 | MIT |
| clsx | 2.1.1 | MIT |
| debug | 4.4.3 | MIT |
| diagram-js | 15.27.1 | MIT |
| diagram-js-direct-editing | 3.5.1 | MIT |
| didi | 11.0.0 | MIT |
| domify | 3.0.0 | MIT |
| dompurify | 3.4.16 | (MPL-2.0 OR Apache-2.0) |
| electron-updater | 6.8.9 | MIT |
| fs-extra | 10.1.0 | MIT |
| graceful-fs | 4.2.11 | ISC |
| highlight.js | 11.12.0 | BSD-3-Clause |
| htm | 3.1.1 | Apache-2.0 |
| ids | 3.0.2 | MIT |
| inherits-browser | 0.1.0 | ISC |
| js-yaml | 4.3.2 | MIT |
| jsonfile | 6.2.1 | MIT |
| lazy-val | 1.0.5 | MIT |
| lodash.escaperegexp | 4.1.2 | MIT |
| lodash.isequal | 4.5.0 | MIT |
| marked | 18.0.14 | MIT |
| min-dash | 5.1.0 | MIT |
| min-dom | 5.3.0 | MIT |
| moddle | 8.2.1 | MIT |
| moddle-xml | 12.3.1 | MIT |
| ms | 2.1.3 | MIT |
| object-refs | 0.4.0 | MIT |
| path-intersection | 4.2.1 | MIT |
| preact | 10.29.8 | MIT |
| qrcode-generator | 2.0.4 | MIT |
| react | 19.3.0 | MIT |
| react-dom | 19.3.0 | MIT |
| sax | 1.6.1 | BlueOak-1.0.0 |
| saxen | 11.2.0 | MIT |
| scheduler | 0.28.0 | MIT |
| semver | 7.7.4 | ISC |
| tiny-svg | 4.1.4 | MIT |
| tiny-typed-emitter | 2.1.0 | MIT |
| universalify | 2.0.1 | MIT |

DOMPurify is used under MPL-2.0 or Apache-2.0 at the user's choice. The app
runs on Electron (MIT), which bundles Chromium and Node.js under their own
licenses (Electron's `LICENSES.chromium.html` ships with the built app).

## bpmn.io License (bpmn-js)

bpmn-js renders the process diagrams in the desktop app. Its license is the MIT
License with a watermark clause; the app keeps the bpmn.io watermark visible on
every rendered diagram, as required:

```
Copyright (c) 2014-present Camunda Services GmbH

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in the
Software without restriction, including without limitation the rights to use, copy,
modify, merge, publish, distribute, sublicense, and/or sell copies of the Software,
and to permit persons to whom the Software is furnished to do so, subject to the
following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

The source code responsible for displaying the bpmn.io project watermark that
links back to https://bpmn.io as part of rendered diagrams MUST NOT be
removed or changed. When this software is being used in a website or application,
the watermark must stay fully visible and not visually overlapped by other elements.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
OR OTHER DEALINGS IN THE SOFTWARE.
```
