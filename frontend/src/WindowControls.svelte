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
  <button class="control minimise" type="button" aria-label="Minimise" onclick={() => Window.Minimise()}>
    <span aria-hidden="true">&#xE921;</span>
  </button>
  <button
    class="control maximise"
    type="button"
    aria-label={maximised ? 'Restore' : 'Maximise'}
    onclick={() => Window.ToggleMaximise()}
  >
    <span aria-hidden="true">{maximised ? '' : ''}</span>
  </button>
  <button class="control close" type="button" aria-label="Close" onclick={() => Window.Close()}>
    <span aria-hidden="true">&#xE8BB;</span>
  </button>
</div>

<style>
  .controls {
    display: flex;
    align-self: stretch;
    --wails-draggable: no-drag;
  }

  .control {
    display: grid;
    place-items: center;
    width: 46px;
    height: 100%;
    padding: 0;
    border: none;
    background: transparent;
    color: var(--muted);
    /* Windows' own caption glyphs (Win 11 / Win 10) */
    font-family: 'Segoe Fluent Icons', 'Segoe MDL2 Assets', sans-serif;
    font-size: 10px;
    cursor: default;
    transition: background 0.15s ease, color 0.15s ease;
  }
  .control:hover {
    background: rgba(255, 255, 255, 0.07);
    color: var(--text);
  }
  .control:active {
    background: rgba(255, 255, 255, 0.04);
  }

  .close:hover {
    background: #c42b1c;
    color: #fff;
  }
  .close:active {
    background: #b22a1b;
    color: #fff;
  }

  .minimise { --wails-non-client-region: minimize; }
  .maximise { --wails-non-client-region: maximize; }
  .close { --wails-non-client-region: close; }
</style>
