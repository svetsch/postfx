var __pfx = (function () {
  function path(o, p) {
    var segs = [];
    String(p).replace(/^\$\.?/, '').replace(/\[(?:'([^']*)'|"([^"]*)"|(-?\d+)|(\*))\]|([^.[\]]+)/g, function (m, a, b, i, w, n) {
      segs.push(a !== undefined ? a : b !== undefined ? b : i !== undefined ? Number(i) : w !== undefined ? '*' : n); return '';
    });
    function walk(cur, k) {
      if (k === segs.length) return { found: true, value: cur };
      var s = segs[k];
      if (cur === null || cur === undefined) return { found: false };
      if (s === '*') { var out = []; (Array.isArray(cur) ? cur : Object.values(cur)).forEach(function (c) { var r = walk(c, k + 1); if (r.found) out.push(r.value); }); return { found: true, value: out }; }
      if (Array.isArray(cur) && s === 'length') return walk(cur.length, k + 1);
      if (typeof s === 'number' && Array.isArray(cur)) { var i = s < 0 ? cur.length + s : s; return i >= 0 && i < cur.length ? walk(cur[i], k + 1) : { found: false }; }
      if (typeof cur !== 'object' || !(s in cur)) return { found: false };
      return walk(cur[s], k + 1);
    }
    return walk(o, 0);
  }
  function text(v) { return v === undefined ? 'undefined' : (v !== null && typeof v === 'object' ? JSON.stringify(v) : String(v)); }
  function deq(a, b) { return JSON.stringify(a) === JSON.stringify(b); }
  function actual(src, prop) {
    switch (src) {
      case 'STATUS': return { found: true, value: pm.response.code };
      case 'RESPONSE_TIME': return { found: true, value: pm.response.responseTime };
      case 'BODY_SIZE': return { found: true, value: pm.response.responseSize };
      case 'BODY': return { found: true, value: pm.response.text() };
      case 'HEADER': var h = pm.response.headers.get(prop); return { found: h !== undefined, value: h };
      case 'JSON_PATH': return path(pm.response.json(), prop);
    }
    throw new Error('unknown source ' + src);
  }
  function eq(a, e) {
    if (typeof a === 'number') return Number(e) === a;
    if (typeof a === 'boolean') return String(a) === String(e).trim().toLowerCase();
    if (a === null) return String(e).trim() === 'null';
    if (typeof a === 'string') {
      if (a === e) return true;
      if (a.trim() !== '' && !isNaN(a) && String(e).trim() !== '' && !isNaN(e) && Number(a) === Number(e)) return true;
      try { return JSON.parse(e) === a; } catch (x) { return false; }
    }
    try { return deq(a, JSON.parse(e)); } catch (x) { return false; }
  }
  function contains(r, e) {
    if (!r.found) return false;
    var v = r.value;
    if (Array.isArray(v)) return v.some(function (x) { return eq(x, e); });
    if (v !== null && typeof v === 'object') return Object.prototype.hasOwnProperty.call(v, e);
    return text(v).indexOf(e) >= 0;
  }
  function empty(r) {
    if (!r.found || r.value === null || r.value === undefined) return true;
    var v = r.value;
    if (typeof v === 'object') return Object.keys(v).length === 0;
    return String(v) === '';
  }
  function type(r) {
    if (!r.found) return 'undefined';
    var v = r.value;
    return v === null ? 'null' : Array.isArray(v) ? 'array' : typeof v;
  }
  function check(src, prop, op, exp) {
    var r = actual(src, prop), v = r.value, s = r.found ? "'" + text(v) + "'" : '<missing>';
    function fail(m) { throw new Error(m); }
    switch (op) {
      case 'EXISTS': if (!r.found) fail('expected value to exist'); break;
      case 'NOT_EXISTS': if (r.found) fail('expected value not to exist but was ' + s); break;
      case 'EQUALS': if (!r.found || !eq(v, exp)) fail('expected ' + s + " to equal '" + exp + "'"); break;
      case 'NOT_EQUALS': if (r.found && eq(v, exp)) fail('expected ' + s + " to not equal '" + exp + "'"); break;
      case 'CONTAINS': if (!contains(r, exp)) fail('expected ' + s + " to contain '" + exp + "'"); break;
      case 'NOT_CONTAINS': if (contains(r, exp)) fail('expected ' + s + " to not contain '" + exp + "'"); break;
      case 'GREATER_THAN': if (!(r.found && Number(v) > Number(exp))) fail('expected ' + s + ' to be > ' + exp); break;
      case 'GREATER_OR_EQUAL': if (!(r.found && Number(v) >= Number(exp))) fail('expected ' + s + ' to be >= ' + exp); break;
      case 'LESS_THAN': if (!(r.found && Number(v) < Number(exp))) fail('expected ' + s + ' to be < ' + exp); break;
      case 'LESS_OR_EQUAL': if (!(r.found && Number(v) <= Number(exp))) fail('expected ' + s + ' to be <= ' + exp); break;
      case 'MATCHES': if (!(r.found && new RegExp(exp).test(text(v)))) fail('expected ' + s + ' to match /' + exp + '/'); break;
      case 'IS_EMPTY': if (!empty(r)) fail('expected ' + s + ' to be empty'); break;
      case 'IS_NOT_EMPTY': if (empty(r)) fail('expected value to not be empty'); break;
      case 'TYPE_IS': if (type(r).toLowerCase() !== String(exp).trim().toLowerCase()) fail('expected type ' + exp + ' but was ' + type(r)); break;
    }
  }
  function extract(src, expr) {
    switch (src) {
      case 'STATUS': return String(pm.response.code);
      case 'BODY': return pm.response.text();
      case 'HEADER': return pm.response.headers.get(expr);
      case 'BODY_REGEX': var m = new RegExp(expr).exec(pm.response.text()); return m ? (m.length > 1 ? m[1] : m[0]) : undefined;
      case 'JSON_PATH': var r; try { r = path(pm.response.json(), expr); } catch (e) { return undefined; } return r.found ? (r.value !== null && typeof r.value === 'object' ? JSON.stringify(r.value) : String(r.value)) : undefined;
    }
  }
  return { check: check, extract: extract };
})();
