import { OAuth2Client } from 'google-auth-library';

const client = new OAuth2Client();

export async function verifyGoogleIdToken(idToken, clientIds) {
  const ticket = await client.verifyIdToken({ idToken, audience: clientIds });
  const payload = ticket.getPayload();
  return {
    googleSub: payload.sub,
    email: payload.email,
    name: payload.name,
    picture: payload.picture,
  };
}
