<script lang="ts">
  import {slide} from 'svelte/transition';
  import {cubicOut} from 'svelte/easing';
  import type {Account} from '../bindings/changeme';

  let {accounts = [], onSignIn = () => {}}: {
    accounts?: Account[];
    onSignIn?: () => void;
  } = $props();

  // The first account is the active one
  let active = $derived(accounts[0]);

  let open = $state(false);
  let root: HTMLElement | undefined = $state();

  function toggle(): void {
    open = !open;
  }

  // Close when clicking anywhere outside the dropdown
  function onWindowPointerDown(e: PointerEvent): void {
    if (open && root && !root.contains(e.target as Node)) open = false;
  }

  function addAccount(): void {
    open = false;
    onSignIn();
  }

  function onWindowKeyDown(e: KeyboardEvent): void {
    if (open && e.key === 'Escape') open = false;
  }
</script>

<svelte:window onpointerdown={onWindowPointerDown} onkeydown={onWindowKeyDown} />

{#if !active}
  <button class="sign-in" type="button" onclick={onSignIn}>Sign in</button>
{:else}
<div class="dropdown" bind:this={root}>
  <button
    class="trigger"
    type="button"
    aria-haspopup="menu"
    aria-expanded={open}
    onclick={toggle}
  >
    <span class="avatar" aria-hidden="true">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
    </span>
    <span class="label">{active.username}</span>
    <svg class="chevron" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="6 9 12 15 18 9"/></svg>
  </button>

  {#if open}
    <div class="menu" role="menu" transition:slide={{duration: 160, easing: cubicOut}}>
      <button class="item item-primary" type="button" role="menuitem" onclick={addAccount}>
        <span>Add account</span>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><line x1="5" y1="12" x2="19" y2="12"/><polyline points="12 5 19 12 12 19"/></svg>
      </button>
    </div>
  {/if}
</div>
{/if}

<style>
  .dropdown {
    --dropdown-bg: rgba(27, 29, 36, 0.92);
    position: relative;
    --wails-draggable: no-drag;
  }

  .sign-in {
    --wails-draggable: no-drag;
    height: 28px;
    padding: 0 16px;
    border: none;
    border-radius: 999px;
    background: var(--accent);
    color: #fff;
    font: inherit;
    font-size: 13px;
    font-weight: 800;
    letter-spacing: 0.01em;
    cursor: pointer;
    transition: filter 0.2s ease, transform 0.1s ease;
  }
  .sign-in:hover { filter: brightness(1.2); }
  .sign-in:active { transform: scale(0.97); }
  .sign-in:focus-visible {
    outline: 2px solid var(--accent-light);
    outline-offset: 2px;
  }

  /* Trigger and menu share one width: the trigger is at least as wide as the
     menu, and the menu stretches to match a wider trigger (long usernames). */
  .trigger {
    display: flex;
    align-items: center;
    gap: 8px;
    width: 100%;
    min-width: 200px;
    height: 28px;
    padding: 0 9px 0 3px;
    border: 1px solid var(--border);
    border-radius: 9px;
    background: var(--surface);
    -webkit-backdrop-filter: blur(12px);
    backdrop-filter: blur(12px);
    color: var(--muted);
    font: inherit;
    font-size: 13px;
    font-weight: 500;
    letter-spacing: 0.005em;
    cursor: pointer;
    transition: color 0.2s ease, border-color 0.2s ease;
  }
  .trigger:hover,
  .trigger[aria-expanded='true'] {
    color: var(--text);
    border-color: var(--accent-light);
  }
  .trigger:focus-visible {
    outline: 2px solid var(--accent-light);
    outline-offset: 2px;
  }
  /* Open: the trigger and menu join into one surface. */
  .trigger[aria-expanded='true'] {
    background: var(--dropdown-bg);
    border-bottom-color: transparent;
    border-bottom-left-radius: 0;
    border-bottom-right-radius: 0;
  }

  .avatar {
    display: grid;
    place-items: center;
    width: 22px;
    height: 22px;
    border-radius: 50%;
    background:
      linear-gradient(var(--surface-solid), var(--surface-solid)) padding-box,
      var(--grad) border-box;
    border: 1.5px solid transparent;
    color: var(--accent-text);
  }
  .avatar svg { width: 12px; height: 12px; }

  .label {
    flex: 1;
    text-align: left;
    white-space: nowrap;
  }

  .chevron {
    width: 13px;
    height: 13px;
    color: var(--subtle);
    transition: transform 0.2s ease, color 0.2s ease;
  }
  .trigger:hover .chevron { color: var(--text); }
  .trigger[aria-expanded='true'] .chevron { transform: rotate(180deg); color: var(--text); }

  .menu {
    position: absolute;
    top: 100%;
    left: 0;
    right: 0;
    padding: 0 6px 6px;
    background: var(--dropdown-bg);
    -webkit-backdrop-filter: blur(16px);
    backdrop-filter: blur(16px);
    border: 1px solid var(--accent-light);
    border-top: none;
    border-radius: 0 0 9px 9px;
    box-shadow: 0 16px 44px rgba(0, 0, 0, 0.5);
    overflow: hidden;
    z-index: 10;
  }
  /* Divider between the button and the menu, spanning the full inner width. */
  .menu::before {
    content: '';
    display: block;
    height: 1px;
    margin: 0 -6px 6px;
    background: var(--border);
  }

  .item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    width: 100%;
    padding: 9px 12px;
    border: none;
    border-radius: 8px;
    background: transparent;
    color: var(--text);
    font: inherit;
    font-size: 14px;
    font-weight: 500;
    text-align: left;
    cursor: pointer;
    transition: transform 0.1s ease, filter 0.2s ease, background 0.2s ease;
  }
  .item svg { width: 16px; height: 16px; }
  .item:hover { background: rgba(255, 255, 255, 0.07); }

  .item-primary {
    background: var(--grad);
    color: #fff;
    font-weight: 600;
    box-shadow: 0 6px 18px var(--accent-glow);
  }
  .item-primary:hover {
    background: var(--grad);
    filter: brightness(1.2);
  }
  .item-primary:active { transform: scale(0.98); }
  .item-primary svg { transition: transform 0.2s ease; }
  .item-primary:hover svg { transform: translateX(2px); }
</style>
