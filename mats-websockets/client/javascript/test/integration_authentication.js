// Register as an UMD module - source: https://github.com/umdjs/umd/blob/master/templates/commonjsStrict.js
(function (root, factory) {
    if (typeof define === 'function' && define.amd) {
        // AMD. Register as an anonymous module.
        define(['chai', 'sinon', 'ws', 'mats', 'env'], factory);
    } else if (typeof exports === 'object' && typeof exports.nodeName !== 'string') {
        // CommonJS
        const chai = require('chai');
        const sinon = require('sinon');
        const ws = require('ws');
        const mats = require('../lib/MatsSocket');

        factory(chai, sinon, ws, mats, process.env);
    } else {
        // Browser globals
        factory(chai, sinon, WebSocket, mats, {});
    }
}(typeof self !== 'undefined' ? self : this, function (chai, sinon, ws, mats, env) {
    const MatsSocket = mats.MatsSocket;

    let logging = false;

    let matsSocket;

    const urls = env.MATS_SOCKET_URLS || "ws://localhost:8080/matssocket,ws://localhost:8081/matssocket";

    function createMatsSocket(urlsToUse = urls) {
        matsSocket = new MatsSocket("TestApp", "1.2.3", urlsToUse.split(","));
        matsSocket.logging = logging;
    }

    function closeMatsSocket() {
        // :: Chill the close slightly, so as to get the final "ACK2" envelope over to delete server's inbox.
        let toClose = matsSocket;
        setTimeout(function () {
            toClose.close("Test done");
        }, 25);
    }

    function setAuth(userId = "standard", expirationTimeMillisSinceEpoch = 20000, roomForLatencyMillis = 10000) {
        const now = Date.now();
        const expiry = now + expirationTimeMillisSinceEpoch;
        matsSocket.setCurrentAuthorization("DummyAuth:" + userId + ":" + expiry, expiry, roomForLatencyMillis);
    }


    describe('MatsSocket integration tests of Authentication & Authorization', function () {
        describe('authorization callbacks', function () {
            beforeEach(() => {
                createMatsSocket();
            });

            afterEach(() => {
                closeMatsSocket();
            });

            beforeEach(() => {
                matsSocket = new MatsSocket("TestApp", "1.2.3", urls.split(","));
                matsSocket.logging = false;
            });

            afterEach(() => {
                // :: Chill the close slightly, so as to get the final "ACKACK" envelope over to delete server's inbox.
                let toClose = matsSocket;
                setTimeout(function () {
                    toClose.close("Test done");
                }, 25);
            });

            it('Should invoke authorization callback before making calls', function (done) {
                let authCallbackCalled = false;

                matsSocket.setAuthorizationExpiredCallback(function (event) {
                    authCallbackCalled = true;
                    setAuth();
                });
                matsSocket.send("Test.single", "SEND_" + matsSocket.id(6), {})
                    .then(reply => {
                        chai.assert(authCallbackCalled);
                        done();
                    });
            });

            it('Should not invoke authorization callback if authorization present', function (done) {
                let authCallbackCalled = false;
                setAuth();
                matsSocket.setAuthorizationExpiredCallback(function (event) {
                    authCallbackCalled = true;
                });
                matsSocket.send("Test.single", "SEND_" + matsSocket.id(6), {})
                    .then(reply => {
                        chai.assert(!authCallbackCalled);
                        done();
                    });
            });

            it('Should invoke authorization callback when expired', function (done) {
                let authCallbackCalled = false;
                setAuth("standard", -20000);
                matsSocket.setAuthorizationExpiredCallback(function (event) {
                    authCallbackCalled = true;
                    setAuth();
                });
                matsSocket.send("Test.single", "SEND_" + matsSocket.id(6), {})
                    .then(reply => {
                        chai.assert(authCallbackCalled);
                        done();
                    });

            });

            it('Should invoke authorization callback when room for latency expired', function (done) {
                let authCallbackCalled = false;
                // Immediately timed out.
                setAuth("standard", 1000, 10000);
                matsSocket.setAuthorizationExpiredCallback(function (event) {
                    authCallbackCalled = true;
                    setAuth();
                });
                matsSocket.send("Test.single", "SEND_" + matsSocket.id(6), {})
                    .then(reply => {
                        chai.assert(authCallbackCalled);
                        done();
                    });
            });
        });

        describe('authorization invalid when Server about to receive or send out information bearing message', function () {
            beforeEach(() => {
                createMatsSocket();
            });

            afterEach(() => {
                closeMatsSocket();
            });

            function testIt(userId, done) {
                setAuth(userId, 2000, 0);

                let authCallbackCalledCount = 0;
                let authCallbackCalledEventType = undefined;
                matsSocket.setAuthorizationExpiredCallback(function (event) {
                    authCallbackCalledCount++;
                    authCallbackCalledEventType = event.type;
                    // This standard auth does not fail reevaluateAuthentication.
                    setAuth();
                });
                let req = {
                    string: "test",
                    number: 15,
                    sleepTime: 10
                };
                let receivedCallbackInvoked = 0;
                // Request to a service that will reply AFTER A DELAY that is long enough that auth shall be expired!
                matsSocket.request("Test.slow", "REQUEST_authentication_from_server_" + matsSocket.id(6), req,
                    function () {
                        receivedCallbackInvoked++;
                    })
                    .then(reply => {
                        let data = reply.data;
                        // Assert that we got receivedCallback ONCE
                        chai.assert.strictEqual(receivedCallbackInvoked, 1, "Should have gotten one, and only one, receivedCallback.");
                        // Assert that we got AuthorizationExpiredEventType.REAUTHENTICATE, and only one call to Auth.
                        chai.assert.strictEqual(authCallbackCalledEventType, mats.AuthorizationRequiredEventType.REAUTHENTICATE, "Should have gotten AuthorizationRequiredEventType.REAUTHENTICATE authorizationExpiredCallback.");
                        chai.assert.strictEqual(authCallbackCalledCount, 1, "authorizationExpiredCallback should only have been invoked once");
                        // Assert data, with the changes from Server side.
                        chai.assert.strictEqual(data.string, req.string + ":FromSlow");
                        chai.assert.strictEqual(data.number, req.number);
                        chai.assert.strictEqual(data.sleepTime, req.sleepTime);
                        done();
                    });
            }

            it('Receive: Using special userId which DummyAuthenticator fails on step reevaluateAuthentication(..), Server shall ask for REAUTH when we perform Request, and when gotten, resolve w/o retransmit (server "holds" message).', function (done) {
                // Using special "userId" for DummySessionAuthenticator that specifically fails @ reevaluateAuthentication(..) step
                testIt("fail_reevaluateAuthentication", done);
            });

            it('Reply from Server: Using special userId which DummyAuthenticator fails on step reevaluateAuthenticationForOutgoingMessage(..), Server shall require REAUTH from Client before sending Reply.', function (done) {
                // Using special "userId" for DummySessionAuthenticator that specifically fails @ reevaluateAuthenticationForOutgoingMessage(..) step
                testIt("fail_reevaluateAuthenticationForOutgoingMessage", done);
            });
        });

        describe('PreConnectionOperation - Authorization upon WebSocket HTTP Handshake', function () {
            beforeEach(() => {
                createMatsSocket();
            });

            afterEach(() => {
                closeMatsSocket();
            });

            it('When preconnectoperations=true, we should get the initial AuthorizationValue presented in Cookie in the authPlugin.checkHandshake(..) function Server-side', function (done) {

                // This is what we're going to test. Cannot be done in Node.js, as there is no common Cookie-jar there.
                matsSocket.preconnectoperation = true;

                // .. skip if in Node.js
                if (typeof (XMLHttpRequest) === 'undefined') {
                    this.skip();
                    return;
                }

                const expiry = Date.now() + 20000;
                const authValue = "DummyAuth:PreConnectionOperation:" + expiry;
                matsSocket.setCurrentAuthorization(authValue, expiry, 5000);

                matsSocket.request("Test.replyWithCookieAuthorization", "PreConnectionOperation_" + matsSocket.id(6), {})
                    .then(value => {
                        // Assert that we get back the Authorization value that we supplied - which comes from the HTTP Handshake Request, evaluated in checkHandshake(..).
                        chai.assert.strictEqual(value.data.string, authValue);
                        done();
                    });
            });

            it('When the test-server\'s PreConnectOperation HTTP Auth-to-Cookie GET returns 400 <= status <= 599, we should eventually get VIOLATED_POLICY.', function (done) {

                // This is what we're going to test. Cannot be done in Node.js, as there is no common Cookie-jar there.
                matsSocket.preconnectoperation = true;
                matsSocket.logging = logging;

                // .. skip if in Node.js
                if (typeof (XMLHttpRequest) === 'undefined') {
                    this.skip();
                    return;
                }

                // Need massive timeout here, as the MatsSocket tries a couple of times (3x number of URLs) before giving up.
                this.timeout(60000);

                matsSocket.setAuthorizationExpiredCallback(function (event) {
                    const expiry = Date.now() + 1000;
                    const authValue = "DummyAuth:fail_preConnectOperationServlet:" + expiry;
                    matsSocket.setCurrentAuthorization(authValue, expiry, 200);
                });

                matsSocket.addSessionClosedEventListener(function (event) {
                    chai.assert.strictEqual(event.type, "close");
                    chai.assert.strictEqual(event.code, mats.MatsSocketCloseCodes.VIOLATED_POLICY);
                    chai.assert.strictEqual(event.codeName, "VIOLATED_POLICY");
                    chai.assert(event.reason.toLowerCase().includes("too many consecutive"));
                    done();
                });

                matsSocket.request("Test.replyWithCookieAuthorization", "PreConnectionOperation_" + matsSocket.id(6), {})
                    .catch(messageEvent => {
                        chai.assert.strictEqual(messageEvent.type, mats.MessageEventType.SESSION_CLOSED);
                    });
            });

            it('When authPlugin.checkHandshake(..) returns false, we should eventually get VIOLATED_POLICY.', function (done) {

                // This is what we're going to test. Cannot be done in Node.js, as there is no common Cookie-jar there.
                matsSocket.preconnectoperation = true;
                matsSocket.logging = logging;

                // .. skip if in Node.js
                if (typeof (XMLHttpRequest) === 'undefined') {
                    this.skip();
                    return;
                }

                // Need massive timeout here, as the MatsSocket tries a couple of times (3x number of URLs) before giving up.
                this.timeout(60000);

                matsSocket.setAuthorizationExpiredCallback(function (event) {
                    const expiry = Date.now() + 1000;
                    const authValue = "DummyAuth:fail_checkHandshake:" + expiry;
                    matsSocket.setCurrentAuthorization(authValue, expiry, 200);
                });

                matsSocket.addSessionClosedEventListener(function (event) {
                    chai.assert.strictEqual(event.type, "close");
                    chai.assert.strictEqual(event.code, mats.MatsSocketCloseCodes.VIOLATED_POLICY);
                    chai.assert.strictEqual(event.codeName, "VIOLATED_POLICY");
                    chai.assert(event.reason.toLowerCase().includes("too many consecutive"));
                    done();
                });

                matsSocket.request("Test.replyWithCookieAuthorization", "PreConnectionOperation_" + matsSocket.id(6), {})
                    .catch(messageEvent => {
                        chai.assert.strictEqual(messageEvent.type, mats.MessageEventType.SESSION_CLOSED);
                    });
            });
        });
    });
}));