import { handleDeviceStatus } from "../../../lib/localAuthUnlockProxy";

// Does this browser hold a device binding (so the sign-in page can offer the PIN pad)? Answered
// from the HttpOnly cookie alone; the device secret never leaves the server.

export const dynamic = "force-dynamic";

export function GET(request: Request) {
  return handleDeviceStatus(request);
}
