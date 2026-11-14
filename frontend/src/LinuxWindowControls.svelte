<script lang="ts">
  import {onMount} from 'svelte';
  import {Window} from '@wailsio/runtime';

  let maximised = $state(false);

  async function syncMaximised(): Promise<void> {
    maximised = await Window.IsMaximised();
  }

  onMount(() => {
    syncMaximised().catch(() => {});
    const onResize = () => syncMaximised().catch(() => {});
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  });
</script>

<div class="controls">
  <button class="control" type="button" aria-label="Minimise" onclick={() => Window.Minimise()}>
    <svg viewBox="0 0 16 16" aria-hidden="true"><line x1="4" y1="8.5" x2="12" y2="8.5" /></svg>
  </button>
  <button
    class="control"
    type="button"
    aria-label={maximised ? 'Restore' : 'Maximise'}
    onclick={() => Window.ToggleMaximise()}
  >
    {#if maximised}
      <svg viewBox="0 0 16 16" aria-hidden="true">
        <rect x="4" y="6" width="6" height="6" rx="1" />
        <path d="M6.5 4h4.5a1 1 0 0 1 1 1v4.5" />
      </svg>
    {:else}
      <svg viewBox="0 0 16 16" aria-hidden="true"><rect x="4.5" y="4.5" width="7" height="7" rx="1" /></svg>
    {/if}
  </button>
  <button class="control close" type="button" aria-label="Close" onclick={() => Window.Close()}>
    <svg viewBox="0 0 16 16" aria-hidden="true">
      <line x1="5" y1="5" x2="11" y2="11" /><line x1="11" y1="5" x2="5" y2="11" />
    </svg>
  </button>
</div>

<style>
  .controls {
    display: flex;
    align-items: center;
    gap: 8px;
    --wails-draggable: no-drag;
  }

  .control {
    display: grid;
    place-items: center;
    width: 24px;
    height: 24px;
    padding: 0;
    border: none;
    border-radius: 50%;
    background: rgba(255, 255, 255, 0.08);
    color: var(--muted);
    cursor: default;
    transition: background 0.15s ease, color 0.15s ease;
  }
  .control:hover {
    background: rgba(255, 255, 255, 0.15);
    color: var(--text);
  }
  .control:active {
    background: rgba(255, 255, 255, 0.22);
  }

  .close:hover {
    background: #c01c28;
    color: #fff;
  }
  .close:active {
    background: #a51d2d;
    color: #fff;
  }

  svg {
    width: 16px;
    height: 16px;
    fill: none;
    stroke: currentColor;
    stroke-width: 1.5;
    stroke-linecap: round;
    stroke-linejoin: round;
  }
</style>
