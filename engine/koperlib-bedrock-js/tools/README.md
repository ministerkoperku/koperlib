# gen_api.mjs

Writes `js/server_api.js` and `js/server_ui_api.js`: every name the real `@minecraft/server` /
`@minecraft/server-ui` modules export that the hand written `server.js` / `server_ui.js` do not have.
Without them an addon importing such a name does not load at all.

```
npm i typescript@5 @minecraft/server@2 @minecraft/server-ui@2
npm pack @minecraft/server@1.19.0 && tar xzf minecraft-server-1.19.0.tgz   # the 1.x names too
node gen_api.mjs ../js/server.js ../js/server_api.js node_modules/@minecraft/server/index.d.ts package/index.d.ts
node gen_api.mjs ../js/server_ui.js ../js/server_ui_api.js node_modules/@minecraft/server-ui/index.d.ts
```

Rerun after adding anything to the hand written files: a name defined by hand and by the
generated tail is a duplicate export and the module does not compile.
