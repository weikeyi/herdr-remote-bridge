const e = new URL(window.__HERDR_ENTRY__ || "/builds/0.25.1-492-b6c9c50c375a6171/index.html", location);
  e.search = location.search;
  e.hash = location.hash;
  location.replace(e);
