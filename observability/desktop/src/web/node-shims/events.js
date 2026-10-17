// node:events for the web bundle (src/web/build.js): the EventEmitter
// src/engine-events.js extends, with only the members it and its callers use.
"use strict";

class EventEmitter {
  constructor() { this._listeners = new Map(); }
  on(type, fn) {
    if (!this._listeners.has(type)) this._listeners.set(type, []);
    this._listeners.get(type).push(fn);
    return this;
  }
  removeListener(type, fn) {
    const list = this._listeners.get(type);
    if (list) this._listeners.set(type, list.filter((f) => f !== fn));
    return this;
  }
  off(type, fn) { return this.removeListener(type, fn); }
  emit(type, ...args) {
    const list = this._listeners.get(type);
    if (!list || !list.length) return false;
    for (const fn of [...list]) fn(...args);
    return true;
  }
}

module.exports = { EventEmitter };
