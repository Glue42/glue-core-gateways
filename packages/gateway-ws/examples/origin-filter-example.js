const gw = require("../lib/gateway-ws.js");

// creating a gateway
gw.configure_logging({
    level: "info",
    appender: console.log
});

const g = gw.create({
    port: 8080,
    security: {
        origin_filters: {
            missing: "whitelist", // native connections that are missing an origin header are allowed
            whitelist: ["#https://.*\.websocket.org", "#chrome-extension://.*"], // only accept connections from the test site and the chrome extensions
            non_matched: "blacklist"
        }
    }
});

// and starting it
g
    .start()
    .catch(err => console.error(err));
