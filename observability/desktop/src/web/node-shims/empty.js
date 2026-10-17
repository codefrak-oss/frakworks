// Every other node: built-in the shared modules name (node:http, node:tls, …)
// in the web bundle (src/web/build.js). Nothing the web version calls reaches
// them: src/engine-data.js's trustSystemCAs is main's only, and src/ticket-data.js's
// VictoriaLogs reads are the transcript's, which the web version omits.
"use strict";

module.exports = {};
