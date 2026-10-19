"use client";

import { useCallback, useEffect, useState } from "react";
import { api } from "../../lib/api";
import { errorMessage } from "../../lib/errors";
import { usePagination } from "../../lib/usePagination";
import type { ChannelUi } from "./useSimpleChannel";

/* eslint-disable @typescript-eslint/no-explicit-any */
export function useDevices({
  selected,
  t,
  setError,
  setSuccess,
  setBusy,
}: { selected: string } & Pick<ChannelUi, "t" | "setError" | "setSuccess" | "setBusy">) {
  // Mobile SDK - real device pairing (core/devices module, #157-159).
  const [pairingCode, setPairingCode] = useState(null);
  const [devices, setDevices] = useState([]);
  const [devicesLoaded, setDevicesLoaded] = useState(false);
  // Revoke + command history, carried over from the retired standalone
  // /devices page (DevicesPanel.js) when it merged into this tab - revoking
  // permanently signs the device out, so it keeps that page's
  // click-again-to-confirm shape rather than firing on one click.
  const [expandedDeviceId, setExpandedDeviceId] = useState(null);
  const [commandsByDevice, setCommandsByDevice] = useState<any>({});
  const [confirmRevokeDeviceId, setConfirmRevokeDeviceId] = useState(null);
  const refreshDevices = useCallback(() => {
    api
      .listDevices()
      .then(setDevices)
      .catch(() => setDevices([]))
      .finally(() => setDevicesLoaded(true));
  }, []);
  useEffect(() => {
    if (selected === "mobile-sdk") refreshDevices();
  }, [selected, refreshDevices]);
  // A device can appear here purely from an action on the phone itself
  // (pairing-code claim or Keycloak sign-in - see MainActivity.kt), with no
  // local mutation on this page to piggyback a refetch onto. Poll while this
  // tab is open, a "cheap GET, short interval" shape, so a freshly-paired phone shows up without a manual
  // reload (the exact bug already fixed once for the admin users list -
  // #112/#145).
  useEffect(() => {
    if (selected !== "mobile-sdk") return undefined;
    const interval = setInterval(refreshDevices, 4000);
    return () => clearInterval(interval);
  }, [selected, refreshDevices]);
  async function handleGeneratePairingCode() {
    setError(null);
    setBusy(true);
    try {
      setPairingCode(await api.generateDevicePairingCode());
    } catch (err) {
      setError(errorMessage(err, t("pairingCodeGenerateError")));
    } finally {
      setBusy(false);
    }
  }

  async function handleRevokeDevice(deviceId: any) {
    setError(null);
    setBusy(true);
    try {
      await api.revokeDevice(deviceId);
      setSuccess(t("deviceRevokedSuccess"));
      refreshDevices();
    } catch (err) {
      setError(errorMessage(err, t("deviceRevokeError")));
    } finally {
      setBusy(false);
    }
  }

  // First click arms the confirm state on that device's card; a second
  // click on the same button (now reading "Confirm revoke") actually fires
  // the API call. Clicking another device's revoke button re-arms for that
  // card instead of confirming the original one - same shape the standalone
  // Devices page used for this exact action.
  function handleRevokeDeviceClick(deviceId: any) {
    if (confirmRevokeDeviceId === deviceId) {
      setConfirmRevokeDeviceId(null);
      void handleRevokeDevice(deviceId);
    } else {
      setConfirmRevokeDeviceId(deviceId);
    }
  }

  async function handleToggleDeviceHistory(deviceId: any) {
    if (expandedDeviceId === deviceId) {
      setExpandedDeviceId(null);
      return;
    }
    setExpandedDeviceId(deviceId);
    if (!commandsByDevice[deviceId]) {
      try {
        const commands = await api.listDeviceCommands(deviceId);
        setCommandsByDevice((prev: any) => ({ ...prev, [deviceId]: commands }));
      } catch (err) {
        setError(errorMessage(err, t("deviceHistoryError")));
      }
    }
  }

  // Shared client-side pagination (lib/usePagination) over the two
  // unbounded record lists this panel renders: the paired-devices list
  // and one device's command history (only ever one expanded at a time,
  // so a single hook keyed off expandedDeviceId covers it).
  const devicesPagination = usePagination(devices);
  const deviceCommandsPagination = usePagination<{
    id: string | number;
    commandType: string;
    status: string;
    createdAt: string;
  }>(expandedDeviceId ? commandsByDevice[expandedDeviceId] || [] : []);
  const { setPage: setDeviceCommandsPage } = deviceCommandsPagination;
  useEffect(() => {
    setDeviceCommandsPage(1);
  }, [expandedDeviceId, setDeviceCommandsPage]);
  return {
    pairingCode,
    devices,
    devicesLoaded,
    expandedDeviceId,
    commandsByDevice,
    confirmRevokeDeviceId,
    setConfirmRevokeDeviceId,
    handleGeneratePairingCode,
    handleToggleDeviceHistory,
    handleRevokeDeviceClick,
    devicesPagination,
    deviceCommandsPagination,
  };
}
