<script lang="ts">
  import AccountDropdown from './AccountDropdown.svelte';
  import type {Account} from './accounts';

  let accounts = $state<Account[]>([]);

  function signIn(): void {
    // TODO: start the login flow.
  }
</script>

<header class="topbar">
  <AccountDropdown {accounts} onSignIn={signIn} />
</header>
