export interface Tokens {
  accessToken: string;
  refreshToken: string;
  accessExpiresAt: string;
  userId: string;
  username: string;
  deviceId: string;
  deviceStatus: string;
  deviceFingerprint: string;
}
export interface Room {
  name: string;
  id: string;
  displayCode: string;
  ownerUserId: string;
  ownerDeviceId: string;
  ownerDeviceFingerprint: string;
  myRole: string;
  myMembershipState: string;
  state: string;
  generation: number;
  mlsEpoch: number;
  messageTtlSeconds: number;
  resetIntervalSeconds: number;
  nextResetAt: string;
  passwordProtected: boolean;
}
export interface Member {
  userId: string;
  username: string;
  state: string;
  role: string;
  deviceFingerprint: string;
}
export interface Invite {
  id: string;
  roomId: string;
  roomDisplayCode: string;
  status: string;
  inviteeUserId: string;
  inviteeUsername: string;
  acceptedDeviceFingerprint: string;
}
export interface Message {
  messageId: string;
  roomId: string;
  generation: number;
  epoch: number;
  senderUserId: string;
  senderUsername: string;
  ciphertext: string;
  createdAt: string;
  expiresAt: string;
}
export interface ChatEvent extends Message {
  type: string;
  detail?: string;
  code?: string;
  idempotencyKey?: string;
}
export interface Device {
  id: string;
  name: string;
  status: string;
  fingerprint: string;
}
