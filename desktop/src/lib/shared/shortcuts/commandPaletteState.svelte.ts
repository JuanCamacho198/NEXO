class CommandPaletteState {
  open = $state(false);

  show(): void {
    this.open = true;
  }

  hide(): void {
    this.open = false;
  }

  toggle(): void {
    this.open = !this.open;
  }
}

export const commandPaletteState = new CommandPaletteState();
