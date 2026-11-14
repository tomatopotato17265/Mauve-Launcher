<script lang="ts">
  import {onMount} from 'svelte';
  import {System} from '@wailsio/runtime';
  import AccountDropdown from './AccountDropdown.svelte';
  import WindowControls from './WindowControls.svelte';
  import LinuxWindowControls from './LinuxWindowControls.svelte';
  import {AuthService, type Account} from '../bindings/changeme';

  let accounts = $state<Account[]>([]);
  let os = $state('');

  async function loadAccounts(): Promise<void> {
    accounts = (await AuthService.GetAccounts()) ?? [];
  }

  async function signIn(): Promise<void> {
    try {
      // Resolves once the user finishes (or closes) the sign-in window
      const account = await AuthService.Login();
      if (account) await loadAccounts();
    } catch (err) {
      console.error('Sign-in failed:', err);
    }
  }

  onMount(() => {
    System.Environment()
      .then((env) => (os = env.OS))
      .catch((err) => console.error('Reading environment failed:', err));
    loadAccounts().catch((err) => console.error('Loading accounts failed:', err));
  });
</script>

<header class="topbar" class:windows={os === 'windows'} class:linux={os === 'linux'}>
  <AccountDropdown {accounts} onSignIn={signIn} />
  {#if os === 'windows'}
    <WindowControls />
  {:else if os === 'linux'}
    <LinuxWindowControls />
  {/if}
</header>
