// Repro #100118: Start new conversation while a past conversation is open in
// History leaves the old transcript on screen (viewingConversation not cleared).
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const React = require("react");
const { FirstMateStrip } = require("../dist/index.cjs");

const OLD = { id: 7, repo: "codefrak-oss/frakworks", claude_session_id: "s1",
  messages: [{ role: "user", text: "about #100116 and #100114", at: "2026-10-17T00:00:00Z" }] };

test("Start new conversation closes a past conversation open in History", async () => {
  const slots = [];
  const fm = {
    startNew: async () => ({ ok: true }),
    conversation: async () => OLD,
  };
  const real = { useState: React.useState, useEffect: React.useEffect, useCallback: React.useCallback };
  const draw = () => {
    let i = 0;
    React.useState = (init) => { const k = i++; if (!(k in slots)) slots[k] = typeof init === "function" ? init() : init;
      return [slots[k], (v) => { slots[k] = typeof v === "function" ? v(slots[k]) : v; }]; };
    React.useEffect = () => {}; React.useCallback = (fn) => fn;
    const had = global.window; global.window = { firstMate: fm };
    try { return FirstMateStrip({ onKickStarter: () => {}, onOpenTicket: () => {} }); }
    finally { Object.assign(React, real); global.window = had; }
  };
  global.window = { firstMate: fm };
  await draw().props.onSelectConversation(7);
  assert.equal(draw().props.viewingConversation, OLD, "past conversation open");
  await draw().props.onNewConversation();
  assert.equal(draw().props.viewingConversation, null, "after Start new conversation the old transcript is still shown");
});
