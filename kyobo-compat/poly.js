/* crema kyobo-compat: ES5 polyfills for Android 4.2 WebKit. Prepended once per page (guarded). */
(function () {
  if (window.__cremaPoly) return;
  window.__cremaPoly = 1;
  var AP = Array.prototype, SP = String.prototype, OP = Object.prototype;
  function def(o, k, v) { if (o && !o[k]) try { Object.defineProperty(o, k, { value: v, configurable: true, writable: true }); } catch (e) { o[k] = v; } }

  def(AP, 'find', function (f, t) { for (var i = 0; i < this.length; i++) if (f.call(t, this[i], i, this)) return this[i]; });
  def(AP, 'findIndex', function (f, t) { for (var i = 0; i < this.length; i++) if (f.call(t, this[i], i, this)) return i; return -1; });
  def(AP, 'includes', function (v, s) { for (var i = s || 0; i < this.length; i++) if (this[i] === v || (v !== v && this[i] !== this[i])) return true; return false; });
  def(AP, 'fill', function (v, s, e) { var n = this.length; s = s || 0; e = e === undefined ? n : e; for (var i = s < 0 ? n + s : s; i < (e < 0 ? n + e : e); i++) this[i] = v; return this; });
  def(Array, 'from', function (a, f, t) { var r = []; if (a == null) return r; if (typeof a === 'string') a = a.split(''); for (var i = 0; i < a.length; i++) r.push(f ? f.call(t, a[i], i) : a[i]); return r; });
  def(Array, 'of', function () { return AP.slice.call(arguments); });
  def(SP, 'includes', function (s, p) { return this.indexOf(s, p || 0) >= 0; });
  def(SP, 'startsWith', function (s, p) { p = p || 0; return this.substr(p, s.length) === s; });
  def(SP, 'endsWith', function (s, l) { var t = String(this); if (l === undefined || l > t.length) l = t.length; return t.substring(l - s.length, l) === s; });
  def(SP, 'repeat', function (n) { var r = ''; for (var i = 0; i < n; i++) r += this; return r; });
  def(SP, 'padStart', function (n, c) { var s = String(this); c = c === undefined ? ' ' : String(c); if (!c || s.length >= n) return s; var p = ''; while (p.length < n - s.length) p += c; return p.slice(0, n - s.length) + s; });
  def(SP, 'trimStart', function () { return this.replace(/^\s+/, ''); });
  def(SP, 'trimEnd', function () { return this.replace(/\s+$/, ''); });
  def(Object, 'assign', function (t) { for (var i = 1; i < arguments.length; i++) { var s = arguments[i]; if (s) for (var k in s) if (OP.hasOwnProperty.call(s, k)) t[k] = s[k]; } return t; });
  def(Object, 'values', function (o) { var r = []; for (var k in o) if (OP.hasOwnProperty.call(o, k)) r.push(o[k]); return r; });
  def(Object, 'entries', function (o) { var r = []; for (var k in o) if (OP.hasOwnProperty.call(o, k)) r.push([k, o[k]]); return r; });
  def(Number, 'isNaN', function (v) { return typeof v === 'number' && v !== v; });
  def(Number, 'isInteger', function (v) { return typeof v === 'number' && isFinite(v) && Math.floor(v) === v; });
  if (window.NodeList) def(NodeList.prototype, 'forEach', AP.forEach);
  if (window.HTMLCollection) def(HTMLCollection.prototype, 'forEach', AP.forEach);

  var EP = window.Element && Element.prototype;
  if (EP) {
    def(EP, 'matches', EP.webkitMatchesSelector);
    def(EP, 'closest', function (sel) { var el = this; while (el && el.nodeType === 1) { if (el.matches(sel)) return el; el = el.parentNode; } return null; });
    def(EP, 'remove', function () { if (this.parentNode) this.parentNode.removeChild(this); });
    if (!('classList' in EP)) {
      var get = function (el) { var c = el.getAttribute('class'); return c ? c.replace(/^\s+|\s+$/g, '').split(/\s+/) : []; };
      var set = function (el, l) { el.setAttribute('class', l.join(' ')); };
      Object.defineProperty(EP, 'classList', { configurable: true, get: function () {
        var el = this;
        return {
          add: function () { var l = get(el); for (var i = 0; i < arguments.length; i++) if (l.indexOf(arguments[i]) < 0) l.push(arguments[i]); set(el, l); },
          remove: function () { var l = get(el); for (var i = 0; i < arguments.length; i++) { var k; while ((k = l.indexOf(arguments[i])) >= 0) l.splice(k, 1); } set(el, l); },
          contains: function (c) { return get(el).indexOf(c) >= 0; },
          toggle: function (c, f) { var has = get(el).indexOf(c) >= 0, on = f === undefined ? !has : !!f; if (on && !has) this.add(c); if (!on && has) this.remove(c); return on; },
          item: function (i) { return get(el)[i] || null; },
          toString: function () { return get(el).join(' '); }
        };
      } });
    }
  }

  if (!window.requestAnimationFrame) {
    window.requestAnimationFrame = window.webkitRequestAnimationFrame || function (cb) { return setTimeout(function () { cb(+new Date()); }, 16); };
    window.cancelAnimationFrame = window.webkitCancelAnimationFrame || function (id) { clearTimeout(id); };
  }

  var P = window.performance || (window.performance = {});
  def(P, 'now', function () { return +new Date(); });
  def(P, 'getEntriesByType', function (t) {
    if (t !== 'navigation') return [];
    var n = P.navigation && P.navigation.type;
    return [{ type: n === 1 ? 'reload' : n === 2 ? 'back_forward' : 'navigate', name: location.href, entryType: 'navigation', startTime: 0 }];
  });
  def(P, 'getEntriesByName', function () { return []; });
  def(P, 'mark', function () {});
  def(P, 'measure', function () {});

  /* Inline <script> blocks in the HTML are not rewritten by the app (only .js/.css are). When one fails
     to parse, the error fires while the parser is still at that script: ask the app for an ES5 version
     (same-origin URL handled in CompatWebViewClient) and insert it right after, so order is kept. */
  var prevErr = window.onerror;
  window.onerror = function (msg, src, line) {
    try {
      if (/SyntaxError/.test(String(msg)) && src && src.split('#')[0] === location.href.split('#')[0]) {
        var ss = document.getElementsByTagName('script'), s = ss[ss.length - 1];
        if (s && !s.src && !s.__crema && s.text) {
          s.__crema = 1;
          var x = new XMLHttpRequest();
          x.open('GET', location.protocol + '//' + location.host + '/__crema_es5__?c=' + encodeURIComponent(s.text), false);
          x.send(null);
          if (x.status === 200 || x.status === 0) {
            var n = document.createElement('script');
            n.__crema = 1;
            n.text = x.responseText;
            s.parentNode.insertBefore(n, s.nextSibling);
            return true;
          }
        }
      }
    } catch (e) {}
    return prevErr ? prevErr.apply(this, arguments) : false;
  };

  try { new window.CustomEvent('x'); } catch (e) {
    window.CustomEvent = function (type, p) { p = p || {}; var ev = document.createEvent('CustomEvent'); ev.initCustomEvent(type, !!p.bubbles, !!p.cancelable, p.detail); return ev; };
  }

  if (typeof window.URL !== 'function' || !('searchParams' in (function () { try { return new window.URL('http://a/?b=1'); } catch (e) { return {}; } })())) {
    var Native = window.URL || window.webkitURL;
    var U = function (url, base) {
      var a = document.createElement('a');
      if (base !== undefined) { var b = document.createElement('a'); b.href = String(base); a.href = b.href; a.href = String(url); } else a.href = String(url);
      if (!/^[a-z][a-z0-9+.-]*:/i.test(a.href)) throw new TypeError('Invalid URL: ' + url);
      var self = this;
      ['href', 'protocol', 'host', 'hostname', 'port', 'pathname', 'search', 'hash'].forEach(function (k) { self[k] = a[k]; });
      if (this.pathname && this.pathname.charAt(0) !== '/') this.pathname = '/' + this.pathname;
      this.origin = this.protocol + '//' + this.host;
      var q = {}, keys = [];
      (this.search || '').replace(/^\?/, '').split('&').forEach(function (kv) { if (!kv) return; var i = kv.indexOf('='), k = decodeURIComponent((i < 0 ? kv : kv.slice(0, i)).replace(/\+/g, ' ')), v = i < 0 ? '' : decodeURIComponent(kv.slice(i + 1).replace(/\+/g, ' ')); if (!(k in q)) { q[k] = []; keys.push(k); } q[k].push(v); });
      this.searchParams = { get: function (k) { return q[k] ? q[k][0] : null; }, getAll: function (k) { return q[k] || []; }, has: function (k) { return k in q; }, keys: function () { return keys.slice(); } };
    };
    U.prototype.toString = function () { return this.href; };
    if (Native) { U.createObjectURL = function (o) { return Native.createObjectURL(o); }; U.revokeObjectURL = function (u) { return Native.revokeObjectURL(u); }; }
    window.URL = U;
  }

  if (!window.Promise) {
    var Pr = function (fn) {
      var st = 0, val, q = [], self = this;
      function run() { if (!st) return; var list = q; q = []; setTimeout(function () { for (var i = 0; i < list.length; i++) list[i](); }, 0); }
      function settle(s, v) { if (st) return; if (s === 1 && v && (typeof v === 'object' || typeof v === 'function')) { var then; try { then = v.then; } catch (e) { return settle(2, e); } if (typeof then === 'function') { var done = 0; try { then.call(v, function (x) { if (!done++) settle(1, x); }, function (x) { if (!done++) settle(2, x); }); } catch (e) { if (!done++) settle(2, e); } return; } } st = s; val = v; run(); }
      this.then = function (ok, ko) {
        return new Pr(function (res, rej) {
          q.push(function () { var cb = st === 1 ? ok : ko; if (typeof cb !== 'function') return (st === 1 ? res : rej)(val); try { res(cb(val)); } catch (e) { rej(e); } });
          run();
        });
      };
      try { fn(function (v) { settle(1, v); }, function (e) { settle(2, e); }); } catch (e) { settle(2, e); }
    };
    Pr.prototype['catch'] = function (f) { return this.then(null, f); };
    Pr.prototype['finally'] = function (f) { return this.then(function (v) { f(); return v; }, function (e) { f(); throw e; }); };
    Pr.resolve = function (v) { return new Pr(function (r) { r(v); }); };
    Pr.reject = function (e) { return new Pr(function (r, j) { j(e); }); };
    Pr.all = function (a) { return new Pr(function (res, rej) { var n = a.length, out = []; if (!n) return res(out); a.forEach(function (p, i) { Pr.resolve(p).then(function (v) { out[i] = v; if (!--n) res(out); }, rej); }); }); };
    Pr.race = function (a) { return new Pr(function (res, rej) { a.forEach(function (p) { Pr.resolve(p).then(res, rej); }); }); };
    // instances created via `new Pr` need prototype methods too
    var Base = Pr;
    window.Promise = function (fn) { var p = new Base(fn); p['catch'] = Pr.prototype['catch']; p['finally'] = Pr.prototype['finally']; return p; };
    window.Promise.resolve = Pr.resolve; window.Promise.reject = Pr.reject; window.Promise.all = Pr.all; window.Promise.race = Pr.race;
  }
})();
