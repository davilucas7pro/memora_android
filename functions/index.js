const functions = require('firebase-functions');
const admin     = require('firebase-admin');

admin.initializeApp();

/**
 * Fires when a new request is created under requests/{token}/{reqId}.
 * Reads the owner's FCM token from /owner/fcmToken and sends a push notification.
 */
exports.notifyNewRequest = functions.database
  .ref('requests/{token}/{reqId}')
  .onCreate(async (snap, context) => {
    const request = snap.val();
    if (!request || request.status !== 'nova') return null;

    // Get the owner's FCM token
    const tokenSnap = await admin.database().ref('owner/fcmToken').once('value');
    const fcmToken  = tokenSnap.val();
    if (!fcmToken) {
      console.log('No FCM token found for owner');
      return null;
    }

    // Get contact name from /contacts/{token}/name (optional, falls back to token)
    let contactName = context.params.token;
    try {
      const contactSnap = await admin.database()
        .ref('contacts/' + context.params.token + '/name')
        .once('value');
      if (contactSnap.val()) contactName = contactSnap.val();
    } catch (_) {}

    const title = '📋 Nova solicitação — ' + contactName;
    const body  = request.title + (request.description ? '\n' + request.description : '');

    const message = {
      token: fcmToken,
      notification: { title, body },
      data: { title, body, token: context.params.token, reqId: context.params.reqId },
      android: {
        priority: 'high',
        notification: {
          channelId: 'memobuild_tasks',
          priority:  'max',
          defaultSound: true
        }
      }
    };

    try {
      const response = await admin.messaging().send(message);
      console.log('FCM sent:', response);
    } catch (err) {
      console.error('FCM error:', err);
    }

    return null;
  });
