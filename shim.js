(function () {
  if (window.GM_getValue || !window.UABNative) return;
  window.GM_getValue = function (k, d) { var v = UABNative.get(k); if (v === null || v === undefined) return d; try { return JSON.parse(v); } catch (e) { return d; } };
  window.GM_setValue = function (k, v) { UABNative.set(k, JSON.stringify(v)); };
  window.GM_setClipboard = function (t) { UABNative.copy(String(t)); };
  var n = 0, cb = {};
  window.GM_xmlhttpRequest = function (o) { var id = ++n; cb[id] = o; UABNative.http(id, String(o.url)); };
  window.__uabHttp = function (id, ok, text) { var o = cb[id]; delete cb[id]; if (!o) return; if (ok) { o.onload && o.onload({responseText: text}); } else { o.onerror && o.onerror(); } };
})();
