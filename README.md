## What is this and who is it for

This is a component of the Glue42 Core project https://github.com/Glue42/core

## Building

![Build](https://github.com/Glue42/glue-core-gateways/workflows/Build/badge.svg)

The Glue Gateways project uses lerna to build. You'll need to execute `lerna bootstrap` once and then `lerna run build`.

## Configuration

### Common

#### Logging configuration

The gateway's logging configuration can be overridden by using the ```configure_logging``` function. Note that the configuration is global and will affect all instances of the gateway.

For example:
```
gw.configure_logging({
                         level: "info",
                         appender: function (log_info)
                         {
                             console.log(log_info);
                         }
                     });
```
The available configuration properties are:
- ```level``` - can be one of the following: ```trace```, ```"debug"```, ```"info"```, ```"warn"``` or ```"error"```, where ```"info"``` is the default option.
- ```appender``` - takes a function that will receive the log info. If not specified, will log to console. The logging object passed to the function has a structure like this:
    ```
    {
        time: 2017-06-22T15:38:34.230Z,
        file: 'C:\\Users\\dimd00d\\AppData\\Local\\Temp\\form-init8247674603237706851.clj',
        output: 'DEBUG [gateway.local-node.core:55] - Sending message {:domain "global", :type :error, :request_id nil, :peer_id nil, :reason_uri "global.errors.authentication.failure", :reason "Unknown authentication method "} to local peer',
        level: 'debug',
        line: 55,
        stacktrace: null,
        namespace: 'gateway.local-node.core',
        message: 'Sending message {:domain "global", :type :error, :request_id nil, :peer_id nil, :reason_uri "global.errors.authentication.failure", :reason "Unknown authentication method "} to local peer'
    }
    ```
    The ```output``` key contains a processed message for direct output where the rest of the keys hold the details.
- ```whitelist``` - array of regexes (for example ```["gateway.*"]```) that describe namespaces to be logged.
- ```blacklist``` - as above, but for namespaces for which logging will be suppressed.

### Web Gateway

#### Client inactivity configuration

In case that a client cannot drop the local connection (for example when used in Worker scenarios, where there is no control over the client disappearing), the gateway can be configured to scavenge clients based on their inactivity (messages not received from the client over a period)

```js
{
    clients: {
        inactive_seconds: 10
    }
}
```

If not specified, the default value is `60` seconds.

### WS Gateway

#### WS Server Configuration

The port to listen to for connections can be specified via the `port` property.

```js
const gateway = gw.create({
    port: 8080
});
```

If not configured, the gateway will listen on the default port `3434`.

#### Websocket Connection Filtering

The gateway supports connection filtering based on the origin header of the connection. The configuration resides under `security\origin_filters` and
the following properties are available:

| key        | type    | default | description                   |
|------------|---------|---------|-------------------------------|
| `whitelist` | array of strings  | empty | list of strings or regex patterns (strings starting with #) that allow an origin |
| `blacklist`   | array of strings | empty | list of strings or regex patterns (strings starting with #) that block an origin |
| `non_matched` | `"whitelist"` or `"blacklist"` | `"whitelist"` | action to take if the origin doesnt match the white or the black list. |
| `missing` | `"whitelist"` or `"blacklist"` | `"whitelist"` | action to take if the origin header is missing |

Example:

```js
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
```

## Code of Conduct

Glue42 has adopted a Code of Conduct that we expect project participants to adhere to. Please [read the full text](https://github.com/Glue42/core/blob/master/CODE_OF_CONDUCT.md) so that you can understand what actions will and will not be tolerated.

## License

Glue42 Core Gateways is [MIT Licensed](https://github.com/Glue42/glue-core-gateways/blob/master/LICENSE).
