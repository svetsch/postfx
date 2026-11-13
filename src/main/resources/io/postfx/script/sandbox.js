/*
 * PostFX script sandbox: a Postman compatible "pm" API on top of a Java bridge.
 * Loaded into a fresh JavaScript context before every pre-request / test script.
 */
(function (global) {
    'use strict';
    const B = global.__bridge;
    try { delete global.__bridge; } catch (e) { /* binding not deletable */ }
    const EVENT = B.event();

    // ------------------------------------------------------------------ helpers
    function inspect(v, depth) {
        depth = depth || 0;
        if (v === undefined) return 'undefined';
        if (v === null) return 'null';
        if (typeof v === 'string') return "'" + v + "'";
        if (typeof v === 'function') return '[Function' + (v.name ? ': ' + v.name : '') + ']';
        if (v instanceof RegExp) return v.toString();
        if (v instanceof Error) return v.name + ': ' + v.message;
        if (typeof v === 'object') {
            if (depth > 2) return Array.isArray(v) ? '[Array]' : '{Object}';
            if (typeof v.toJSON === 'function') v = v.toJSON();
            if (Array.isArray(v)) return '[ ' + v.map(x => inspect(x, depth + 1)).join(', ') + ' ]';
            const keys = Object.keys(v);
            if (!keys.length) return '{}';
            return '{ ' + keys.map(k => k + ': ' + inspect(v[k], depth + 1)).join(', ') + ' }';
        }
        return String(v);
    }

    function toStr(v) {
        if (v === undefined) return undefined;
        if (v === null) return 'null';
        if (typeof v === 'object') return JSON.stringify(v);
        return String(v);
    }

    function typeOf(v) {
        if (v === null) return 'null';
        if (Array.isArray(v)) return 'array';
        if (v instanceof Date) return 'date';
        if (v instanceof RegExp) return 'regexp';
        if (v instanceof Error) return 'error';
        return typeof v;
    }

    function deepEqual(a, b) {
        if (a === b) return true;
        if (typeof a === 'number' && typeof b === 'number') return isNaN(a) && isNaN(b);
        if (a === null || b === null || typeof a !== 'object' || typeof b !== 'object') return false;
        if (Array.isArray(a) !== Array.isArray(b)) return false;
        if (a instanceof Date && b instanceof Date) return a.getTime() === b.getTime();
        const ka = Object.keys(a), kb = Object.keys(b);
        if (ka.length !== kb.length) return false;
        for (const k of ka) {
            if (!Object.prototype.hasOwnProperty.call(b, k) || !deepEqual(a[k], b[k])) return false;
        }
        return true;
    }

    function pathSegments(path) {
        if (Array.isArray(path)) return path;
        const out = [];
        String(path).replace(/^\$\.?/, '').replace(/\[(?:'([^']*)'|"([^"]*)"|(-?\d+)|\*)\]|([^.[\]]+)/g,
            function (m, q1, q2, idx, name) {
                if (q1 !== undefined) out.push(q1);
                else if (q2 !== undefined) out.push(q2);
                else if (idx !== undefined) out.push(Number(idx));
                else if (name !== undefined) out.push(name);
                return '';
            });
        return out;
    }

    function getPath(obj, path) {
        const segs = pathSegments(path);
        let cur = obj;
        for (const s of segs) {
            if (cur === null || cur === undefined) return { found: false };
            if (typeof s === 'number' && Array.isArray(cur)) {
                const i = s < 0 ? cur.length + s : s;
                if (i < 0 || i >= cur.length) return { found: false };
                cur = cur[i];
            } else {
                if (typeof cur !== 'object' && typeof cur !== 'string') return { found: false };
                if (!(s in Object(cur))) return { found: false };
                cur = cur[s];
            }
        }
        return { found: true, value: cur };
    }

    // ------------------------------------------------------------------ console
    function fmt(args) {
        return Array.prototype.map.call(args, function (a) {
            if (typeof a === 'string') return a;
            if (a instanceof Error) return a.name + ': ' + a.message;
            if (a === undefined) return 'undefined';
            if (typeof a === 'function') return String(a);
            try { return JSON.stringify(a, null, 2); } catch (e) { return String(a); }
        }).join(' ');
    }
    global.console = {
        log: function () { B.log('log', fmt(arguments)); },
        info: function () { B.log('info', fmt(arguments)); },
        warn: function () { B.log('warn', fmt(arguments)); },
        error: function () { B.log('error', fmt(arguments)); },
        debug: function () { B.log('debug', fmt(arguments)); },
        clear: function () {}
    };

    // ------------------------------------------------------------------ timers (run after the script body)
    const timers = [];
    let timerSeq = 1;
    global.setTimeout = function (fn, ms) {
        const args = Array.prototype.slice.call(arguments, 2);
        const id = timerSeq++;
        timers.push({ id: id, fn: fn, ms: Number(ms) || 0, args: args, seq: id });
        return id;
    };
    global.clearTimeout = function (id) {
        const i = timers.findIndex(t => t.id === id);
        if (i >= 0) timers.splice(i, 1);
    };
    global.setImmediate = function (fn) { return global.setTimeout(fn, 0); };
    global.setInterval = function () { B.log('warn', 'setInterval is not supported in PostFX scripts'); return 0; };
    global.clearInterval = function () {};

    // ------------------------------------------------------------------ base64
    global.btoa = function (s) { return B.btoa(String(s)); };
    global.atob = function (s) { return B.atob(String(s)); };

    // ------------------------------------------------------------------ assertions (chai compatible subset)
    class AssertionError extends Error {
        constructor(message) { super(message); this.name = 'AssertionError'; }
    }

    function Assertion(obj, message) {
        this._obj = obj;
        this._flags = {};
        this._message = message;
    }
    const A = Assertion.prototype;

    A._assert = function (ok, positive, negative) {
        const neg = !!this._flags.negate;
        if (neg ? ok : !ok) {
            const msg = neg ? negative : positive;
            throw new AssertionError((this._message ? this._message + ': ' : '') + msg);
        }
        return this;
    };

    function chain(name, getter) {
        Object.defineProperty(A, name, { configurable: true, get: getter });
    }
    ['to', 'be', 'been', 'is', 'that', 'which', 'and', 'has', 'have', 'with', 'at', 'of', 'same', 'but', 'does', 'still', 'also']
        .forEach(w => chain(w, function () { return this; }));
    chain('not', function () { this._flags.negate = !this._flags.negate; return this; });
    chain('deep', function () { this._flags.deep = true; return this; });
    chain('nested', function () { this._flags.nested = true; return this; });
    chain('own', function () { this._flags.own = true; return this; });
    chain('any', function () { this._flags.any = true; this._flags.all = false; return this; });
    chain('all', function () { this._flags.all = true; this._flags.any = false; return this; });
    chain('ordered', function () { this._flags.ordered = true; return this; });

    function method(names, fn) {
        [].concat(names).forEach(n => { A[n] = fn; });
    }

    function chainableMethod(names, fn, chainBehaviour) {
        [].concat(names).forEach(function (n) {
            Object.defineProperty(A, n, {
                configurable: true,
                get: function () {
                    const self = this;
                    if (chainBehaviour) chainBehaviour.call(self);
                    const callable = function () { return fn.apply(self, arguments); };
                    Object.setPrototypeOf(callable, self);
                    return callable;
                }
            });
        });
    }

    function isResponse(o) { return o && o.__isResponse === true; }

    function property(name, fn) { chain(name, function () { fn.call(this); return this; }); }

    // value assertions
    property('ok', function () {
        if (isResponse(this._obj)) {
            const c = this._obj.code;
            this._assert(c >= 200 && c < 300, 'expected response code to be 2XX but found ' + c, 'expected response code to not be 2XX but found ' + c);
        } else {
            this._assert(!!this._obj, 'expected ' + inspect(this._obj) + ' to be truthy', 'expected ' + inspect(this._obj) + ' to be falsy');
        }
    });
    property('true', function () { this._assert(this._obj === true, 'expected ' + inspect(this._obj) + ' to be true', 'expected ' + inspect(this._obj) + ' to be false'); });
    property('false', function () { this._assert(this._obj === false, 'expected ' + inspect(this._obj) + ' to be false', 'expected ' + inspect(this._obj) + ' to be true'); });
    property('null', function () { this._assert(this._obj === null, 'expected ' + inspect(this._obj) + ' to be null', 'expected ' + inspect(this._obj) + ' not to be null'); });
    property('undefined', function () { this._assert(this._obj === undefined, 'expected ' + inspect(this._obj) + ' to be undefined', 'expected ' + inspect(this._obj) + ' not to be undefined'); });
    property('NaN', function () { this._assert(typeof this._obj === 'number' && isNaN(this._obj), 'expected ' + inspect(this._obj) + ' to be NaN', 'expected ' + inspect(this._obj) + ' not to be NaN'); });
    property('exist', function () { this._assert(this._obj !== null && this._obj !== undefined, 'expected ' + inspect(this._obj) + ' to exist', 'expected ' + inspect(this._obj) + ' to not exist'); });
    property('finite', function () { this._assert(typeof this._obj === 'number' && isFinite(this._obj), 'expected ' + inspect(this._obj) + ' to be a finite number', 'expected ' + inspect(this._obj) + ' to not be a finite number'); });
    property('empty', function () {
        const o = this._obj;
        let empty;
        if (typeof o === 'string' || Array.isArray(o)) empty = o.length === 0;
        else if (o instanceof Map || o instanceof Set) empty = o.size === 0;
        else if (o && typeof o === 'object') empty = Object.keys(o).length === 0;
        else throw new AssertionError('.empty was passed non-string primitive ' + inspect(o));
        this._assert(empty, 'expected ' + inspect(o) + ' to be empty', 'expected ' + inspect(o) + ' not to be empty');
    });

    method(['equal', 'equals', 'eq'], function (val, msg) {
        if (msg) this._message = msg;
        const ok = this._flags.deep ? deepEqual(this._obj, val) : this._obj === val;
        return this._assert(ok, 'expected ' + inspect(this._obj) + ' to ' + (this._flags.deep ? 'deeply ' : '') + 'equal ' + inspect(val),
            'expected ' + inspect(this._obj) + ' to not ' + (this._flags.deep ? 'deeply ' : '') + 'equal ' + inspect(val));
    });
    method(['eql', 'eqls'], function (val, msg) {
        if (msg) this._message = msg;
        return this._assert(deepEqual(this._obj, val), 'expected ' + inspect(this._obj) + ' to deeply equal ' + inspect(val),
            'expected ' + inspect(this._obj) + ' to not deeply equal ' + inspect(val));
    });

    function subject(a) {
        if (a._flags.doLength) {
            const o = a._obj;
            return o instanceof Map || o instanceof Set ? o.size : o.length;
        }
        return a._obj;
    }
    function cmp(names, test, word) {
        method(names, function (n, msg) {
            if (msg) this._message = msg;
            const v = subject(this);
            const what = this._flags.doLength ? inspect(this._obj) + ' to have a length ' : inspect(v) + ' to be ';
            return this._assert(test(v, n), 'expected ' + what + word + ' ' + n, 'expected ' + what.replace(' to ', ' to not ') + word + ' ' + n);
        });
    }
    cmp(['above', 'gt', 'greaterThan'], (a, b) => a > b, 'above');
    cmp(['least', 'gte', 'greaterThanOrEqual'], (a, b) => a >= b, 'at least');
    cmp(['below', 'lt', 'lessThan'], (a, b) => a < b, 'below');
    cmp(['most', 'lte', 'lessThanOrEqual'], (a, b) => a <= b, 'at most');
    method('within', function (lo, hi, msg) {
        if (msg) this._message = msg;
        const v = subject(this);
        return this._assert(v >= lo && v <= hi, 'expected ' + inspect(v) + ' to be within ' + lo + '..' + hi,
            'expected ' + inspect(v) + ' to not be within ' + lo + '..' + hi);
    });
    method(['closeTo', 'approximately'], function (expected, delta, msg) {
        if (msg) this._message = msg;
        return this._assert(Math.abs(this._obj - expected) <= delta, 'expected ' + inspect(this._obj) + ' to be close to ' + expected + ' +/- ' + delta,
            'expected ' + inspect(this._obj) + ' not to be close to ' + expected + ' +/- ' + delta);
    });

    chainableMethod(['a', 'an'], function (type, msg) {
        if (msg) this._message = msg;
        const t = String(type).toLowerCase();
        const actual = typeOf(this._obj);
        const article = /^[aeiou]/.test(t) ? 'an ' : 'a ';
        return this._assert(actual === t, 'expected ' + inspect(this._obj) + ' to be ' + article + t,
            'expected ' + inspect(this._obj) + ' not to be ' + article + t);
    });

    chainableMethod(['include', 'includes', 'contain', 'contains'], function (val, msg) {
        if (msg) this._message = msg;
        const o = this._obj;
        let ok;
        if (typeof o === 'string') ok = o.indexOf(val) >= 0;
        else if (Array.isArray(o)) ok = this._flags.deep ? o.some(x => deepEqual(x, val)) : o.indexOf(val) >= 0;
        else if (o instanceof Set) ok = o.has(val);
        else if (o instanceof Map) ok = Array.from(o.values()).indexOf(val) >= 0;
        else if (o && typeof o === 'object' && val && typeof val === 'object') {
            const self = this;
            ok = Object.keys(val).every(k => self._flags.deep ? deepEqual(o[k], val[k]) : o[k] === val[k]);
        } else ok = false;
        return this._assert(ok, 'expected ' + inspect(o) + ' to include ' + inspect(val), 'expected ' + inspect(o) + ' to not include ' + inspect(val));
    }, function () { this._flags.contains = true; });

    chainableMethod(['length', 'lengthOf'], function (n, msg) {
        if (msg) this._message = msg;
        const o = this._obj;
        const len = o instanceof Map || o instanceof Set ? o.size : (o == null ? undefined : o.length);
        return this._assert(len === n, 'expected ' + inspect(o) + ' to have a length of ' + n + ' but got ' + len,
            'expected ' + inspect(o) + ' to not have a length of ' + n);
    }, function () { this._flags.doLength = true; });

    method('property', function (name, val) {
        const o = this._obj;
        let found, value;
        if (this._flags.nested) {
            const r = getPath(o, name);
            found = r.found; value = r.value;
        } else if (this._flags.own) {
            found = o != null && Object.prototype.hasOwnProperty.call(o, name);
            value = found ? o[name] : undefined;
        } else {
            found = o != null && (name in Object(o));
            value = found ? o[name] : undefined;
        }
        if (arguments.length > 1) {
            const eq = this._flags.deep ? deepEqual(value, val) : value === val;
            this._assert(found && eq, 'expected ' + inspect(o) + ' to have property ' + inspect(name) + ' of ' + inspect(val) + (found ? ', but got ' + inspect(value) : ''),
                'expected ' + inspect(o) + ' to not have property ' + inspect(name) + ' of ' + inspect(val));
        } else {
            this._assert(found, 'expected ' + inspect(o) + ' to have property ' + inspect(name), 'expected ' + inspect(o) + ' to not have property ' + inspect(name));
        }
        if (!this._flags.negate) this._obj = value;
        return this;
    });
    method(['ownProperty', 'haveOwnProperty'], function (name, val) {
        this._flags.own = true;
        return arguments.length > 1 ? this.property(name, val) : this.property(name);
    });
    method('nestedProperty', function (name, val) {
        this._flags.nested = true;
        return arguments.length > 1 ? this.property(name, val) : this.property(name);
    });

    method(['keys', 'key'], function (keys) {
        let expected = keys;
        if (arguments.length > 1) expected = Array.prototype.slice.call(arguments);
        else if (!Array.isArray(keys)) expected = typeof keys === 'object' && keys !== null ? Object.keys(keys) : [keys];
        const actual = Object.keys(this._obj || {});
        let ok;
        if (this._flags.any) ok = expected.some(k => actual.indexOf(k) >= 0);
        else {
            ok = expected.every(k => actual.indexOf(k) >= 0);
            if (!this._flags.contains) ok = ok && actual.length === expected.length;
        }
        return this._assert(ok, 'expected ' + inspect(this._obj) + ' to have ' + (this._flags.any ? 'any of ' : '') + 'keys ' + inspect(expected),
            'expected ' + inspect(this._obj) + ' to not have keys ' + inspect(expected));
    });
    method('members', function (list) {
        const o = this._obj || [];
        const self = this;
        const has = (arr, x) => self._flags.deep ? arr.some(y => deepEqual(x, y)) : arr.indexOf(x) >= 0;
        let ok;
        if (this._flags.contains) ok = list.every(x => has(o, x));
        else if (this._flags.ordered) ok = o.length === list.length && list.every((x, i) => self._flags.deep ? deepEqual(x, o[i]) : x === o[i]);
        else ok = o.length === list.length && list.every(x => has(o, x)) && o.every(x => has(list, x));
        return this._assert(ok, 'expected ' + inspect(o) + ' to have the same members as ' + inspect(list),
            'expected ' + inspect(o) + ' to not have the same members as ' + inspect(list));
    });
    method(['match', 'matches'], function (re, msg) {
        if (msg) this._message = msg;
        return this._assert(re.test(String(this._obj)), 'expected ' + inspect(this._obj) + ' to match ' + re, 'expected ' + inspect(this._obj) + ' not to match ' + re);
    });
    method('string', function (s, msg) {
        if (msg) this._message = msg;
        return this._assert(String(this._obj).indexOf(s) >= 0, 'expected ' + inspect(this._obj) + ' to contain ' + inspect(s), 'expected ' + inspect(this._obj) + ' to not contain ' + inspect(s));
    });
    method('oneOf', function (list, msg) {
        if (msg) this._message = msg;
        const self = this;
        const ok = list.some(x => self._flags.deep ? deepEqual(x, self._obj) : x === self._obj);
        return this._assert(ok, 'expected ' + inspect(this._obj) + ' to be one of ' + inspect(list), 'expected ' + inspect(this._obj) + ' to not be one of ' + inspect(list));
    });
    method(['instanceOf', 'instanceof'], function (C, msg) {
        if (msg) this._message = msg;
        return this._assert(this._obj instanceof C, 'expected ' + inspect(this._obj) + ' to be an instance of ' + (C && C.name), 'expected ' + inspect(this._obj) + ' to not be an instance of ' + (C && C.name));
    });
    method(['satisfy', 'satisfies'], function (fn, msg) {
        if (msg) this._message = msg;
        return this._assert(!!fn(this._obj), 'expected ' + inspect(this._obj) + ' to satisfy ' + String(fn), 'expected ' + inspect(this._obj) + ' to not satisfy ' + String(fn));
    });
    method(['throw', 'throws', 'Throw'], function (expected) {
        let thrown = null;
        try { this._obj(); } catch (e) { thrown = e; }
        let ok = thrown !== null;
        if (ok && expected instanceof RegExp) ok = expected.test(thrown.message);
        else if (ok && typeof expected === 'string') ok = String(thrown.message).indexOf(expected) >= 0;
        else if (ok && typeof expected === 'function') ok = thrown instanceof expected;
        return this._assert(ok, 'expected function to throw' + (expected ? ' ' + inspect(expected) : ''), 'expected function to not throw but it threw ' + inspect(thrown));
    });
    method('jsonSchema', function (schema) {
        let target = this._obj;
        if (isResponse(target)) target = target.json();
        const errors = [];
        validateSchema(target, schema, '$', errors);
        return this._assert(errors.length === 0, 'expected data to satisfy schema but found error: ' + errors.join('; '), 'expected data to not satisfy schema');
    });

    // response assertions: pm.response.to.have.status(200) ...
    method('status', function (expected) {
        const r = this._obj;
        if (!isResponse(r)) throw new AssertionError('status assertion can only be used on pm.response');
        if (typeof expected === 'number') {
            return this._assert(r.code === expected, 'expected response to have status code ' + expected + ' but got ' + r.code,
                'expected response to not have status code ' + expected);
        }
        return this._assert(r.status === expected, 'expected response to have status reason ' + inspect(expected) + ' but got ' + inspect(r.status),
            'expected response to not have status reason ' + inspect(expected));
    });
    method('header', function (name, value) {
        const r = this._obj;
        const actual = r.headers.get(name);
        if (arguments.length > 1) {
            return this._assert(actual !== undefined && actual === value, "expected response to have header '" + name + "' with value " + inspect(value) + ' but got ' + inspect(actual),
                "expected response to not have header '" + name + "' with value " + inspect(value));
        }
        return this._assert(actual !== undefined, "expected response to have header with key '" + name + "'", "expected response to not have header with key '" + name + "'");
    });
    method('body', function (expected) {
        const text = this._obj.text();
        if (arguments.length === 0) return this._assert(text.length > 0, 'expected response to have content in body', 'expected response to not have content in body');
        if (expected instanceof RegExp) return this._assert(expected.test(text), 'expected response body to match ' + expected, 'expected response body to not match ' + expected);
        if (typeof expected === 'object') {
            let parsed; try { parsed = JSON.parse(text); } catch (e) { parsed = undefined; }
            return this._assert(deepEqual(parsed, expected), 'expected response body json to equal ' + inspect(expected) + ' but got ' + inspect(parsed), 'expected response body json to not equal ' + inspect(expected));
        }
        return this._assert(text === String(expected), 'expected response body to equal ' + inspect(expected) + ' but got ' + inspect(text), 'expected response body to not equal ' + inspect(expected));
    });
    method('jsonBody', function (path, value) {
        let parsed, isJson = true;
        try { parsed = JSON.parse(this._obj.text()); } catch (e) { isJson = false; }
        if (arguments.length === 0) return this._assert(isJson, 'expected response body to be a valid json', 'expected response body to not be a valid json');
        if (!isJson) return this._assert(false, 'expected response body to be a valid json', 'expected response body to be a valid json');
        if (typeof path === 'object') return this._assert(deepEqual(parsed, path), 'expected response body json to equal ' + inspect(path), 'expected response body json to not equal ' + inspect(path));
        const r = getPath(parsed, path);
        if (arguments.length === 1) return this._assert(r.found, 'expected ' + inspect(parsed) + ' to have property ' + inspect(path), 'expected ' + inspect(parsed) + ' to not have property ' + inspect(path));
        return this._assert(r.found && deepEqual(r.value, value), 'expected response body json at ' + inspect(path) + ' to contain ' + inspect(value) + ' but got ' + inspect(r.value),
            'expected response body json at ' + inspect(path) + ' to not contain ' + inspect(value));
    });
    method('responseTime', function () { return this; });
    function statusClass(name, test, label) {
        property(name, function () {
            const c = this._obj.code;
            this._assert(test(c), 'expected response code to be ' + label + ' but found ' + c, 'expected response code to not be ' + label + ' but found ' + c);
        });
    }
    statusClass('info', c => c >= 100 && c < 200, '1XX');
    statusClass('success', c => c >= 200 && c < 300, '2XX');
    statusClass('redirection', c => c >= 300 && c < 400, '3XX');
    statusClass('clientError', c => c >= 400 && c < 500, '4XX');
    statusClass('serverError', c => c >= 500 && c < 600, '5XX');
    statusClass('error', c => c >= 400 && c < 600, '4XX or 5XX');
    statusClass('accepted', c => c === 202, '202');
    statusClass('badRequest', c => c === 400, '400');
    statusClass('unauthorized', c => c === 401, '401');
    statusClass('forbidden', c => c === 403, '403');
    statusClass('notFound', c => c === 404, '404');
    statusClass('rateLimited', c => c === 429, '429');
    property('json', function () {
        let ok = true;
        try { JSON.parse(this._obj.text()); } catch (e) { ok = false; }
        this._assert(ok, 'expected response body to be a valid json', 'expected response body to not be a valid json');
    });
    property('withBody', function () {
        this._assert(this._obj.text().length > 0, 'expected response to have content in body', 'expected response to not have content in body');
    });

    function validateSchema(data, schema, path, errors) {
        if (!schema || typeof schema !== 'object') return;
        if (schema.type) {
            const types = [].concat(schema.type);
            const t = typeOf(data);
            const ok = types.some(x => x === t || (x === 'integer' && t === 'number' && Number.isInteger(data)) || (x === 'number' && t === 'number'));
            if (!ok) { errors.push(path + ' should be ' + types.join('|') + ' but is ' + t); return; }
        }
        if (schema.enum && !schema.enum.some(e => deepEqual(e, data))) errors.push(path + ' should be one of ' + JSON.stringify(schema.enum));
        if (schema.const !== undefined && !deepEqual(schema.const, data)) errors.push(path + ' should equal ' + JSON.stringify(schema.const));
        if (typeof data === 'number') {
            if (schema.minimum !== undefined && data < schema.minimum) errors.push(path + ' should be >= ' + schema.minimum);
            if (schema.maximum !== undefined && data > schema.maximum) errors.push(path + ' should be <= ' + schema.maximum);
        }
        if (typeof data === 'string') {
            if (schema.minLength !== undefined && data.length < schema.minLength) errors.push(path + ' should have at least ' + schema.minLength + ' characters');
            if (schema.maxLength !== undefined && data.length > schema.maxLength) errors.push(path + ' should have at most ' + schema.maxLength + ' characters');
            if (schema.pattern && !new RegExp(schema.pattern).test(data)) errors.push(path + ' should match pattern ' + schema.pattern);
        }
        if (Array.isArray(data)) {
            if (schema.minItems !== undefined && data.length < schema.minItems) errors.push(path + ' should have at least ' + schema.minItems + ' items');
            if (schema.maxItems !== undefined && data.length > schema.maxItems) errors.push(path + ' should have at most ' + schema.maxItems + ' items');
            if (schema.items) data.forEach((d, i) => validateSchema(d, schema.items, path + '[' + i + ']', errors));
        }
        if (data && typeof data === 'object' && !Array.isArray(data)) {
            (schema.required || []).forEach(k => { if (!(k in data)) errors.push(path + " should have required property '" + k + "'"); });
            const props = schema.properties || {};
            Object.keys(props).forEach(k => { if (k in data) validateSchema(data[k], props[k], path + '.' + k, errors); });
            if (schema.additionalProperties === false) {
                Object.keys(data).forEach(k => { if (!(k in props)) errors.push(path + " should not have additional property '" + k + "'"); });
            }
        }
        ['allOf'].forEach(key => (schema[key] || []).forEach(s => validateSchema(data, s, path, errors)));
        if (schema.anyOf) {
            const ok = schema.anyOf.some(s => { const e = []; validateSchema(data, s, path, e); return e.length === 0; });
            if (!ok) errors.push(path + ' should match some schema in anyOf');
        }
    }

    function expect(v, message) { return new Assertion(v, message); }
    expect.fail = function (message) { throw new AssertionError(message || 'expect.fail()'); };

    // ------------------------------------------------------------------ property lists (headers, query...)
    function PropertyList(items) {
        this._list = (items || []).map(i => ({ key: i.key, value: i.value, disabled: !!i.disabled, type: i.type }));
    }
    PropertyList.prototype = {
        _find: function (key) {
            const k = String(key).toLowerCase();
            return this._list.find(i => !i.disabled && String(i.key).toLowerCase() === k);
        },
        get: function (key) { const i = this._find(key); return i ? i.value : undefined; },
        one: function (key) { const i = this._find(key); return i ? { key: i.key, value: i.value } : undefined; },
        has: function (key, value) {
            const i = this._find(key);
            return arguments.length > 1 ? !!i && i.value === value : !!i;
        },
        add: function (item) {
            if (typeof item === 'string') {
                const idx = item.indexOf(':');
                item = idx >= 0 ? { key: item.substring(0, idx).trim(), value: item.substring(idx + 1).trim() } : { key: item, value: '' };
            }
            this._list.push({ key: item.key, value: toStr(item.value) || '', disabled: !!item.disabled });
        },
        append: function (item) { this.add(item); },
        upsert: function (item) {
            const i = this._find(item.key);
            if (i) i.value = toStr(item.value) || ''; else this.add(item);
        },
        remove: function (predicate) {
            if (typeof predicate === 'function') this._list = this._list.filter(i => !predicate(i));
            else {
                const k = String(predicate).toLowerCase();
                this._list = this._list.filter(i => String(i.key).toLowerCase() !== k);
            }
        },
        clear: function () { this._list = []; },
        count: function () { return this._list.filter(i => !i.disabled).length; },
        all: function () { return this._list.filter(i => !i.disabled).map(i => ({ key: i.key, value: i.value })); },
        each: function (fn) { this.all().forEach(fn); },
        map: function (fn) { return this.all().map(fn); },
        filter: function (fn) { return this.all().filter(fn); },
        find: function (fn) { return this.all().find(fn); },
        toObject: function () {
            const o = {};
            this._list.forEach(i => { if (!i.disabled) o[i.key] = i.value; });
            return o;
        },
        toJSON: function () { return this._list; },
        toString: function () { return this.all().map(i => i.key + ': ' + i.value).join('\n'); }
    };

    // ------------------------------------------------------------------ url
    function parseQuery(qs) {
        if (!qs) return [];
        return qs.split('&').filter(p => p.length).map(p => {
            const eq = p.indexOf('=');
            return eq < 0 ? { key: p, value: null } : { key: p.substring(0, eq), value: p.substring(eq + 1) };
        });
    }
    function Url(raw) { this.update(raw); }
    Url.prototype = {
        update: function (raw) {
            raw = String(raw === undefined || raw === null ? '' : raw);
            const q = raw.indexOf('?');
            this._base = q >= 0 ? raw.substring(0, q) : raw;
            this.query = new PropertyList(parseQuery(q >= 0 ? raw.substring(q + 1) : ''));
        },
        getQueryString: function () {
            return this.query._list.filter(i => !i.disabled).map(i => i.value === null || i.value === undefined ? i.key : i.key + '=' + i.value).join('&');
        },
        toString: function () {
            const qs = this.getQueryString();
            return this._base + (qs ? '?' + qs : '');
        },
        getRaw: function () { return this.toString(); },
        get protocol() { const m = /^([a-z][a-z0-9+.-]*):\/\//i.exec(this._base); return m ? m[1] : undefined; },
        _hostPart: function () { return this._base.replace(/^[a-z][a-z0-9+.-]*:\/\//i, '').split('/')[0]; },
        get host() { return this._hostPart().split(':')[0].split('.'); },
        get port() { const p = this._hostPart().split(':')[1]; return p; },
        get path() { const parts = this._base.replace(/^[a-z][a-z0-9+.-]*:\/\//i, '').split('/'); parts.shift(); return parts; },
        getHost: function () { return this._hostPart().split(':')[0]; },
        getPath: function () { return '/' + this.path.join('/'); },
        getPathWithQuery: function () { const qs = this.getQueryString(); return this.getPath() + (qs ? '?' + qs : ''); },
        addQueryParams: function (params) {
            const self = this;
            if (typeof params === 'string') parseQuery(params).forEach(p => self.query.add(p));
            else [].concat(params).forEach(p => self.query.add(p));
        },
        removeQueryParams: function (names) {
            const self = this;
            [].concat(names).forEach(n => self.query.remove(typeof n === 'object' ? n.key : n));
        },
        toJSON: function () { return this.toString(); }
    };

    // ------------------------------------------------------------------ variable scopes
    function scope(name) {
        return {
            get: function (k) { const v = B.getVar(name, String(k)); return v === null || v === undefined ? undefined : v; },
            set: function (k, v) { B.setVar(name, String(k), toStr(v) === undefined ? '' : toStr(v)); },
            unset: function (k) { B.unsetVar(name, String(k)); },
            has: function (k) { return B.hasVar(name, String(k)); },
            clear: function () { B.clearScope(name); },
            toObject: function () { return JSON.parse(B.scopeJson(name)); },
            replaceIn: function (s) { return B.replaceIn(String(s)); }
        };
    }

    const variables = scope('local');
    variables.get = function (k) { const v = B.getVar('all', String(k)); return v === null || v === undefined ? undefined : v; };
    variables.has = function (k) { return B.hasVar('all', String(k)); };
    variables.toObject = function () { return JSON.parse(B.scopeJson('all')); };

    const environment = scope('environment');
    Object.defineProperty(environment, 'name', { get: function () { return B.environmentName(); } });

    const iterationData = scope('data');
    iterationData.set = function () { throw new Error('pm.iterationData is read-only'); };

    // ------------------------------------------------------------------ request
    const reqData = JSON.parse(B.requestJson());
    function makeBody(b) {
        b = b || { mode: 'none' };
        const body = {
            mode: b.mode,
            raw: b.raw,
            urlencoded: new PropertyList(b.urlencoded),
            formdata: new PropertyList(b.formdata),
            graphql: b.graphql || {},
            options: b.options || {},
            update: function (v) {
                if (typeof v === 'string') { this.mode = 'raw'; this.raw = v; }
                else if (v && v.mode) {
                    this.mode = v.mode;
                    if (v.raw !== undefined) this.raw = v.raw;
                    if (v.urlencoded) this.urlencoded = new PropertyList(v.urlencoded);
                    if (v.formdata) this.formdata = new PropertyList(v.formdata);
                    if (v.graphql) this.graphql = v.graphql;
                }
            },
            isEmpty: function () { return !this.mode || this.mode === 'none' || (this.mode === 'raw' && !this.raw); },
            toString: function () { return this.mode === 'raw' ? (this.raw || '') : ''; },
            toJSON: function () {
                return { mode: this.mode, raw: this.raw, urlencoded: this.urlencoded.toJSON(), formdata: this.formdata.toJSON(), graphql: this.graphql, options: this.options };
            }
        };
        return body;
    }
    const request = {
        id: reqData.id,
        name: reqData.name,
        method: reqData.method,
        url: new Url(reqData.url),
        headers: new PropertyList(reqData.headers),
        body: makeBody(reqData.body),
        auth: reqData.auth,
        addHeader: function (h) { this.headers.add(h); },
        removeHeader: function (k) { this.headers.remove(k); },
        upsertHeader: function (h) { this.headers.upsert(h); },
        addQueryParams: function (p) { this.url.addQueryParams(p); },
        removeQueryParams: function (p) { this.url.removeQueryParams(p); },
        toJSON: function () {
            return { id: this.id, name: this.name, method: this.method, url: this.url.toString(), headers: this.headers.toJSON(), body: this.body.toJSON(), auth: this.auth };
        }
    };
    // allow "pm.request.url = '...'"
    let urlObject = request.url;
    Object.defineProperty(request, 'url', {
        enumerable: true,
        get: function () { return urlObject; },
        set: function (v) { urlObject = v instanceof Url ? v : new Url(String(v)); }
    });

    // ------------------------------------------------------------------ response
    function makeResponse(d) {
        let parsed, parsedOk = false;
        const headers = new PropertyList(d.headers);
        return {
            __isResponse: true,
            id: d.id,
            code: d.code,
            status: d.status,
            responseTime: d.responseTime,
            responseSize: d.responseSize,
            headers: headers,
            text: function () { return d.body; },
            json: function () {
                if (!parsedOk) {
                    try { parsed = JSON.parse(d.body); }
                    catch (e) { throw new SyntaxError('JSONError: ' + e.message + ' (response body is not valid JSON)'); }
                    parsedOk = true;
                }
                return parsed;
            },
            reason: function () { return d.status; },
            size: function () { return { body: d.responseSize, header: 0, total: d.responseSize }; },
            get stream() { return d.body; },
            get to() { return new Assertion(this); },
            get not() { const a = new Assertion(this); a._flags.negate = true; return a; },
            toJSON: function () { return { code: d.code, status: d.status, headers: headers.toJSON(), body: d.body, responseTime: d.responseTime }; }
        };
    }
    const resJson = B.responseJson();
    const response = resJson ? makeResponse(JSON.parse(resJson)) : undefined;

    function parseCookies(res) {
        const out = {};
        if (!res) return out;
        res.headers._list.filter(h => String(h.key).toLowerCase() === 'set-cookie').forEach(h => {
            const first = String(h.value).split(';')[0];
            const eq = first.indexOf('=');
            if (eq > 0) out[first.substring(0, eq).trim()] = first.substring(eq + 1).trim();
        });
        return out;
    }
    const cookieMap = parseCookies(response);
    const cookies = {
        get: function (n) { return cookieMap[n]; },
        has: function (n) { return Object.prototype.hasOwnProperty.call(cookieMap, n); },
        toObject: function () { return Object.assign({}, cookieMap); },
        all: function () { return Object.keys(cookieMap).map(k => ({ name: k, value: cookieMap[k] })); }
    };

    // ------------------------------------------------------------------ tests
    function errorMessage(e) {
        if (e && e.name && e.message !== undefined) return e.name + ': ' + e.message;
        return String(e);
    }
    function test(name, fn) {
        try {
            if (typeof fn === 'function') {
                if (fn.length > 0) fn(function (err) { if (err) throw err; });
                else fn();
            }
            B.addTest(String(name), true, null, false);
        } catch (e) {
            B.addTest(String(name), false, errorMessage(e), false);
        }
        return test;
    }
    test.skip = function (name) { B.addTest(String(name), true, null, true); return test; };

    // ------------------------------------------------------------------ sendRequest
    function normalizeHeaders(h) {
        if (!h) return [];
        if (h instanceof PropertyList) return h.toJSON();
        if (typeof h === 'string') return h.split('\n').filter(Boolean).map(l => { const i = l.indexOf(':'); return { key: l.substring(0, i).trim(), value: l.substring(i + 1).trim() }; });
        if (Array.isArray(h)) return h.map(x => typeof x === 'string' ? normalizeHeaders(x)[0] : { key: x.key, value: toStr(x.value) });
        return Object.keys(h).map(k => ({ key: k, value: toStr(h[k]) }));
    }
    function sendRequest(req, callback) {
        const spec = typeof req === 'string' ? { url: req } : (req || {});
        const s = {
            url: String(spec.url),
            method: String(spec.method || 'GET'),
            headers: normalizeHeaders(spec.header || spec.headers),
            body: spec.body ? {
                mode: spec.body.mode || 'raw',
                raw: typeof spec.body.raw === 'object' ? JSON.stringify(spec.body.raw) : spec.body.raw,
                urlencoded: spec.body.urlencoded ? normalizeHeaders(spec.body.urlencoded) : undefined,
                formdata: spec.body.formdata ? normalizeHeaders(spec.body.formdata) : undefined
            } : null
        };
        const out = JSON.parse(B.sendRequest(JSON.stringify(s)));
        const err = out.error ? new Error(out.error) : null;
        const res = err ? null : makeResponse(out);
        if (typeof callback === 'function') {
            try { callback(err, res); }
            catch (e) { B.log('error', 'Error in pm.sendRequest callback: ' + errorMessage(e)); throw e; }
            return undefined;
        }
        return err ? Promise.reject(err) : Promise.resolve(res);
    }

    // ------------------------------------------------------------------ crypto (CryptoJS compatible subset)
    function WordArray(hex) { this._hex = hex; this.sigBytes = hex.length / 2; }
    WordArray.prototype.toString = function (encoder) { return (encoder || Hex).stringify(this); };
    WordArray.prototype.concat = function (other) { this._hex += other._hex; this.sigBytes = this._hex.length / 2; return this; };
    const Hex = { stringify: wa => wa._hex, parse: s => new WordArray(String(s).toLowerCase()) };
    const Utf8 = { stringify: wa => B.hexToUtf8(wa._hex), parse: s => new WordArray(B.utf8ToHex(String(s))) };
    const Latin1 = { stringify: wa => B.hexToLatin1(wa._hex), parse: s => new WordArray(B.latin1ToHex(String(s))) };
    const Base64 = { stringify: wa => B.hexToBase64(wa._hex), parse: s => new WordArray(B.base64ToHex(String(s))) };
    function toWA(x) { return x instanceof WordArray ? x : Utf8.parse(x === undefined || x === null ? '' : String(x)); }
    const CryptoJS = {
        enc: { Hex: Hex, Utf8: Utf8, Latin1: Latin1, Base64: Base64 },
        lib: { WordArray: { create: function () { return new WordArray(''); }, random: function (n) { return new WordArray(B.randomHex(n)); } } }
    };
    [['MD5', 'MD5'], ['SHA1', 'SHA-1'], ['SHA224', 'SHA-224'], ['SHA256', 'SHA-256'], ['SHA384', 'SHA-384'], ['SHA512', 'SHA-512']].forEach(function (p) {
        CryptoJS[p[0]] = function (msg) { return new WordArray(B.hash(p[1], toWA(msg)._hex)); };
    });
    [['HmacMD5', 'HmacMD5'], ['HmacSHA1', 'HmacSHA1'], ['HmacSHA224', 'HmacSHA224'], ['HmacSHA256', 'HmacSHA256'], ['HmacSHA384', 'HmacSHA384'], ['HmacSHA512', 'HmacSHA512']].forEach(function (p) {
        CryptoJS[p[0]] = function (msg, key) { return new WordArray(B.hmac(p[1], toWA(key)._hex, toWA(msg)._hex)); };
    });

    // ------------------------------------------------------------------ lodash subset
    const _ = {
        get: function (o, path, def) { const r = getPath(o, path); return r.found && r.value !== undefined ? r.value : def; },
        has: function (o, path) { return getPath(o, path).found; },
        isEqual: deepEqual,
        isEmpty: function (v) { if (v == null) return true; if (typeof v === 'string' || Array.isArray(v)) return v.length === 0; if (typeof v === 'object') return Object.keys(v).length === 0; return true; },
        isArray: Array.isArray,
        isString: v => typeof v === 'string',
        isNumber: v => typeof v === 'number',
        isObject: v => v !== null && (typeof v === 'object' || typeof v === 'function'),
        keys: o => Object.keys(o || {}),
        values: o => Object.keys(o || {}).map(k => o[k]),
        each: function (c, fn) { if (Array.isArray(c)) c.forEach(fn); else Object.keys(c || {}).forEach(k => fn(c[k], k)); return c; },
        map: function (c, fn) { if (typeof fn === 'string') { const p = fn; fn = x => _.get(x, p); } return Array.isArray(c) ? c.map(fn) : Object.keys(c || {}).map(k => fn(c[k], k)); },
        filter: (c, fn) => (c || []).filter(fn),
        find: (c, fn) => (c || []).find(fn),
        includes: (c, v) => (typeof c === 'string' || Array.isArray(c)) ? c.indexOf(v) >= 0 : Object.keys(c || {}).some(k => c[k] === v),
        uniq: a => Array.from(new Set(a)),
        sample: a => a[Math.floor(Math.random() * a.length)],
        random: function (lo, hi) { if (hi === undefined) { hi = lo; lo = 0; } return Math.floor(Math.random() * (hi - lo + 1)) + lo; },
        times: (n, fn) => Array.from({ length: n }, (x, i) => fn(i)),
        cloneDeep: v => v === undefined ? v : JSON.parse(JSON.stringify(v)),
        sortBy: (a, k) => a.slice().sort((x, y) => { const vx = typeof k === 'function' ? k(x) : x[k], vy = typeof k === 'function' ? k(y) : y[k]; return vx < vy ? -1 : vx > vy ? 1 : 0; }),
        first: a => a[0],
        last: a => a[a.length - 1],
        size: c => Array.isArray(c) || typeof c === 'string' ? c.length : Object.keys(c || {}).length,
        pick: (o, ks) => [].concat(ks).reduce((r, k) => { if (o && k in o) r[k] = o[k]; return r; }, {}),
        omit: (o, ks) => Object.keys(o || {}).filter(k => [].concat(ks).indexOf(k) < 0).reduce((r, k) => { r[k] = o[k]; return r; }, {})
    };
    _.forEach = _.each;

    const uuid = { v4: function () { return B.uuid(); } };

    global.require = function (name) {
        switch (name) {
            case 'chai': return { expect: expect, AssertionError: AssertionError };
            case 'crypto-js': return CryptoJS;
            case 'lodash': return _;
            case 'uuid': return uuid;
            case 'btoa': return global.btoa;
            case 'atob': return global.atob;
            default: throw new Error("Module '" + name + "' is not available in PostFX scripts");
        }
    };

    // ------------------------------------------------------------------ pm
    const info = JSON.parse(B.infoJson());
    const pm = {
        info: info,
        variables: variables,
        environment: environment,
        collectionVariables: scope('collection'),
        globals: scope('global'),
        iterationData: iterationData,
        request: request,
        response: response,
        cookies: cookies,
        test: test,
        expect: expect,
        sendRequest: sendRequest,
        execution: {
            setNextRequest: function (n) { B.setNextRequest(n === null || n === undefined ? null : String(n)); },
            skipRequest: function () { B.skipRequest(); },
            location: [info.requestName]
        },
        visualizer: { set: function () { B.log('info', 'pm.visualizer is not supported in PostFX'); }, clear: function () {} },
        require: global.require
    };
    pm.setNextRequest = pm.execution.setNextRequest;

    global.pm = pm;
    global.CryptoJS = CryptoJS;
    global._ = _;
    global.chai = { expect: expect };
    global.AssertionError = AssertionError;

    // ------------------------------------------------------------------ legacy (postman.* / tests[] / responseBody...)
    global.tests = {};
    global.postman = {
        setEnvironmentVariable: (k, v) => environment.set(k, v),
        getEnvironmentVariable: k => environment.get(k),
        clearEnvironmentVariable: k => environment.unset(k),
        clearEnvironmentVariables: () => environment.clear(),
        setGlobalVariable: (k, v) => pm.globals.set(k, v),
        getGlobalVariable: k => pm.globals.get(k),
        clearGlobalVariable: k => pm.globals.unset(k),
        clearGlobalVariables: () => pm.globals.clear(),
        setNextRequest: n => pm.execution.setNextRequest(n),
        getResponseHeader: n => response ? response.headers.get(n) : undefined,
        getResponseCookie: n => cookieMap[n] === undefined ? undefined : { name: n, value: cookieMap[n] }
    };
    global.environment = environment.toObject();
    global.globals = pm.globals.toObject();
    global.data = iterationData.toObject();
    global.iteration = info.iteration;
    global.request = {
        id: reqData.id, name: reqData.name, method: reqData.method, url: reqData.url,
        headers: request.headers.toObject(),
        data: reqData.body && reqData.body.mode === 'raw' ? reqData.body.raw : (reqData.body && reqData.body.mode === 'urlencoded' ? new PropertyList(reqData.body.urlencoded).toObject() : {})
    };
    if (response) {
        global.responseBody = response.text();
        global.responseCode = { code: response.code, name: response.status, detail: response.status };
        global.responseHeaders = response.headers.toObject();
        global.responseTime = response.responseTime;
        global.responseCookies = cookies.all();
    }
    global.xml2Json = function (xml) { return JSON.parse(B.xmlToJson(String(xml))); };

    // ------------------------------------------------------------------ finish hook, called by the host after the user script
    Object.defineProperty(global, '__finish', {
        enumerable: false,
        value: function () {
            let guard = 0;
            while (timers.length && guard++ < 10000) {
                timers.sort((a, b) => a.ms - b.ms || a.seq - b.seq);
                const t = timers.shift();
                try { t.fn.apply(null, t.args); }
                catch (e) { B.log('error', 'Error in timer callback: ' + errorMessage(e)); }
            }
            Object.keys(global.tests).forEach(function (k) {
                const ok = !!global.tests[k];
                B.addTest(k, ok, ok ? null : 'AssertionError: tests["' + k + '"] evaluated to ' + inspect(global.tests[k]), false);
            });
            if (EVENT === 'prerequest') B.updateRequest(JSON.stringify(request.toJSON()));
        }
    });
})(this);
