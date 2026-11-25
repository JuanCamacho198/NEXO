if (typeof URL.parse !== 'function') {
  URL.parse = (u, b) => {
    try {
      return new URL(u, b);
    } catch {
      return null;
    }
  };
}
