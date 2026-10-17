// electron-builder.js — the packaged build's config (was package.json's
// `build` key; electron-builder reads this file by name, so
// .github/workflows/desktop-release.yml's `npx electron-builder` call is
// unchanged). It adds the commit stamp (#100091): CI's GITHUB_SHA goes into
// the packaged package.json as `commit` (src/app-update.js's running commit)
// and into latest-mac.yml as `releaseNotes` (the incoming build's commit,
// which electron-updater hands over on update-downloaded). A build made
// outside Actions has no GITHUB_SHA and is not stamped.
"use strict";

const commit = process.env.GITHUB_SHA || null;

module.exports = {
  "appId": "org.frakworks.agent-army",
    "productName": "frakworks",
    "protocols": [
      {
        "name": "frakworks",
        "schemes": [
          "frakworks"
        ]
      }
    ],
    "directories": {
      "output": "release"
    },
    "files": [
      "build/icon.png",
      "src/**",
      "dist/**",
      "package.json",
      "build/entitlements.mac.plist",
      "!dist/*.cjs",
      "!dist/**/*.map"
    ],
    "mac": {
      "target": [
        {
          "target": "dmg",
          "arch": [
            "arm64"
          ]
        },
        {
          "target": "zip",
          "arch": [
            "arm64"
          ]
        }
      ],
      "category": "public.app-category.developer-tools",
      "hardenedRuntime": true,
      "gatekeeperAssess": false,
      "entitlements": "build/entitlements.mac.plist",
      "entitlementsInherit": "build/entitlements.mac.plist",
      "extendInfo": {
        "NSAppleEventsUsageDescription": "frakworks opens a First mate conversation in Claude Code in a new iTerm2 window."
      }
    },
    "linux": {
      "target": [
        "dir"
      ]
    },
    "dmg": {
      "writeUpdateInfo": false
    },
    "publish": [
      {
        "provider": "generic",
        "url": "http://releases.example/",
        "channel": "latest"
      }
    ],
    "artifactName": "${productName}-${version}-${arch}.${ext}",
    "extraResources": [
      {
        "from": "../../shared/first-mate-prompt.json",
        "to": "first-mate-prompt.json"
      }
    ],
  extraMetadata: { commit },
  ...(commit ? { releaseInfo: { releaseNotes: commit } } : {}),
};
