<script lang="ts">
  import {onMount} from 'svelte';
  import AccountDropdown from './AccountDropdown.svelte';
  import {AuthService, type Account} from '../bindings/changeme';

  let accounts = $state<Account[]>([]);

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
    loadAccounts().catch((err) => console.error('Loading accounts failed:', err));
  });
</script>

<header class="topbar">
  <AccountDropdown {accounts} onSignIn={signIn} />
</header>
