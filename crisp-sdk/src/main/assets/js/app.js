window.CRISP_RUNTIME_CONFIG = {
  lock_maximized: true,
  lock_full_view: true
};

window.$crisp = [];

function initialize() {
  var _document = document;
  var _script = _document.createElement("script");

  _script.src = "https://client.crisp.chat/l.js";
  _script.async = 1;
  _document.getElementsByTagName("head")[0].appendChild(_script);
}