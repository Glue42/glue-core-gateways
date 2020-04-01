import { configure_logging, create } from '@glue42/pwa-gateway/pwa/pwa-gateway';

function fetch_user(login) {
    return login;
}

function generate_access_token(login) {
    return Buffer.from(JSON.stringify({ login: login })).toString('base64');
}

function parse_access_token(token) {
    return JSON.parse(Buffer.from(token, 'base64').toString())
}

function success(login, token) {
    return Promise.resolve(
        {
            type: 'success',
            login: login,
            user: fetch_user(login),
            access_token: token
        })
}

function handle_user_pass_authentication(auth_details) {
    const login = auth_details.login
    if (login != 'root') {
        return Promise.reject('Invalid account')
    }

    return success(login, generate_access_token(login));
}

function handle_access_token_authentication(auth_details) {
    const token = auth_details.token;
    if (!token) {
        return Promise.reject('Missing token')
    }

    try {
        const details = parse_access_token(token)
        const login = details.login
        if (login != 'root') {
            return Promise.reject('Invalid account')
        }

        return success(login, token);
    } catch (error) {
        return Promise.reject('Invalid token' + error)
    }
}

function custom_auth(request) {
    console.log('received authentication request', request)

    const auth_details = request.authentication;
    if (!auth_details) {
        return Promise.reject('Missing authentication details');
    }

    const method = auth_details.method

    switch (method) {
        case 'secret': return handle_user_pass_authentication(auth_details);
        case 'access-token': return handle_access_token_authentication(auth_details);
        default: return Promise.reject('Invalid authentication method: ' + method);
    }
}

// setup the gateway

configure_logging({
    level: 'info',
    appender: function (entry) {
        console.log(entry.output);
    }
});


const g = create({
    authentication: {
        authenticator: custom_auth
    }
});

g.start()
    .then(() => g.connect((_client, msg) => {
        console.log('client received a message');
        console.log(msg);
        if (msg.domain === 'global' && msg.type === 'welcome') {
            console.log('client authenticated');
        }
    }))
    .then(client => {
        client.send({
            type: 'hello',
            request_id: 'r-8b4bcc9fa2d5457084cedf08d3f093cd-3731601',
            identity: {
                application: 'app',
                instance: 'instance-3731601'
            },
            authentication: {
                method: 'secret',
                login: 'root',
                secret: 'password'
            }
        });
    })
    .catch(err => console.error(err));
