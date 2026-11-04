/* crema kyobo-compat debug (only when /sdcard/kyobo_compat.debug exists):
   logs visible overlay/popup elements (z-index >= 900) to the console (logcat "Web Console"). */
(function () {
  if (window.__cremaDbg) return;
  window.__cremaDbg = 1;
  var last = '';
  function desc(el) {
    var cs = getComputedStyle(el), r = el.getBoundingClientRect();
    return el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') + (el.className && typeof el.className === 'string' ? '.' + el.className.replace(/\s+/g, '.') : '') +
      ' z=' + cs.zIndex + ' pos=' + cs.position + ' disp=' + cs.display + ' rect=' + Math.round(r.left) + ',' + Math.round(r.top) + ' ' + Math.round(r.width) + 'x' + Math.round(r.height) +
      ' wt=' + (cs.webkitTransform || '') + ' style="' + (el.getAttribute('style') || '').slice(0, 160) + '" html=' + el.innerHTML.replace(/\s+/g, ' ').slice(0, 160);
  }
  function scan() {
    try {
      var out = [], all = document.body ? document.body.getElementsByTagName('*') : [];
      for (var i = 0; i < all.length; i++) {
        var cs = getComputedStyle(all[i]);
        if (cs.display !== 'none' && cs.visibility !== 'hidden' && parseInt(cs.zIndex, 10) >= 900) out.push(desc(all[i]));
      }
      var s = out.join('\n');
      if (s !== last) { last = s; console.log('[crema-dbg] ' + location.pathname + ' overlays=' + out.length + '\n' + s); }
    } catch (e) { console.log('[crema-dbg] ' + e); }
  }
  setInterval(scan, 2000);
  var prev = window.onerror;
  window.onerror = function (m, src, line) {
    console.log('[crema-dbg] error ' + m + ' @' + src + ':' + line);
    return prev ? prev.apply(this, arguments) : false;
  };
})();
