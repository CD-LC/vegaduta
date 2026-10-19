import { useEffect, useMemo, useState, type FormEvent } from "react";
import { api } from "../lib/api";
import { toast } from "../lib/toastBus";
import { usePagination } from "../lib/usePagination";
import type { useConfirm } from "../components/admin/ConfirmDialog";
import { BLANK_HOSTED_PROVIDER_DRAFT, hostedRowOverridesFromRow, hostedRowOverridesToDraft, hostedRowOverridesToPayload, type HostedProviderRow } from "./constants";

/**
 * State and actions of the platform-hosted LLM provider pool card (split out
 * of page.tsx): the rows, the pool master switch, the add/edit form draft,
 * model discovery, and the region/search filters with paging. The page's
 * Refresh loads the rows and the pool switch through the returned setters.
 */
export function useHostedPool(askConfirm: ReturnType<typeof useConfirm>) {
  const [hostedProviders, setHostedProviders] = useState([]);
  const [hostedProvidersError, setHostedProvidersError] = useState(null);
  const [hostedProviderRegionFilter, setHostedProviderRegionFilter] = useState("");
  const [hostedProviderDraft, setHostedProviderDraft] = useState(BLANK_HOSTED_PROVIDER_DRAFT);
  const [editingHostedProviderId, setEditingHostedProviderId] = useState(null); // null = add-new form
  const [poolSettings, setPoolSettings] = useState(null); // { enabled, updatedAt, updatedBy } - the master switch
  const [poolSettingsBusy, setPoolSettingsBusy] = useState(false);
  const [savingHostedProvider, setSavingHostedProvider] = useState(false);
  const [deletingHostedProviderId, setDeletingHostedProviderId] = useState(null);
  const [togglingHostedProviderId, setTogglingHostedProviderId] = useState(null);
  const [hostedQuery, setHostedQuery] = useState("");
  const [genericProviderModels, setGenericProviderModels] = useState([]); // catalog dropdown for the "provider" mode
  const [loadingGenericProviderModels, setLoadingGenericProviderModels] = useState(false);
  const [discoveredHostedModels, setDiscoveredHostedModels] = useState([]); // live-probed for "custom" mode
  const [discoveringHostedModels, setDiscoveringHostedModels] = useState(false);

  function startEditHostedProvider(row: HostedProviderRow) {
    setEditingHostedProviderId(row.id);
    setHostedProviderDraft({
      mode: row.providerName ? "provider" : "custom",
      label: row.label,
      baseUrl: row.baseUrl,
      model: row.model,
      apiKey: "",
      enabled: row.enabled,
      priority: row.priority,
      region: row.region || "",
      completionsPath: row.completionsPath || "",
      providerName: row.providerName || "",
      authType: row.authType || "API_KEY",
      vertexProjectId: row.vertexProjectId || "",
      vertexLocation: row.vertexLocation || "us-central1",
      zeroCost: Boolean(row.zeroCost),
      ...hostedRowOverridesToDraft(row),
    });
    setDiscoveredHostedModels([]);
  }

  function cancelHostedProviderEdit() {
    setEditingHostedProviderId(null);
    setHostedProviderDraft(BLANK_HOSTED_PROVIDER_DRAFT);
    setDiscoveredHostedModels([]);
  }

  // Warms the model dropdown for "provider" mode from the same cached
  // catalog the tenant BYOK picker reads (GET /api/providers/{provider}/models)
  // - instant, no API key needed, since these ids don't depend on this
  // specific row's key. Runs whenever the picked providerName changes.
  useEffect(() => {
    if (hostedProviderDraft.mode !== "provider" || !hostedProviderDraft.providerName) {
      setGenericProviderModels([]);
      return undefined;
    }
    let cancelled = false;
    setLoadingGenericProviderModels(true);
    api
      .listProviderModels(hostedProviderDraft.providerName)
      .then((models) => {
        if (!cancelled) setGenericProviderModels(models || []);
      })
      .catch(() => {
        if (!cancelled) setGenericProviderModels([]);
      })
      .finally(() => {
        if (!cancelled) setLoadingGenericProviderModels(false);
      });
    return () => {
      cancelled = true;
    };
  }, [hostedProviderDraft.mode, hostedProviderDraft.providerName]);
  async function handleDiscoverHostedModels() {
    const draft = hostedProviderDraft;
    if (!draft.baseUrl.trim() || !draft.apiKey.trim()) {
      toast.error("Enter a base URL and API key first, then discover models.");
      return;
    }
    setDiscoveringHostedModels(true);
    try {
      const result = await api.discoverHostedLlmModels(
        draft.baseUrl.trim(),
        draft.apiKey.trim(),
        draft.completionsPath.trim() || null
      );
      const models = result?.models || [];
      setDiscoveredHostedModels(models);
      if (models.length === 0) {
        toast.error(
          "Could not discover any models for this base URL/key - check they're correct, or enter a model id manually."
        );
      } else {
        toast.success(
          `Found ${models.length} model${models.length === 1 ? "" : "s"} - pick one from the Model dropdown.`
        );
      }
    } catch (err) {
      toast.error((err as Error).message || "Could not discover models for this provider.");
    } finally {
      setDiscoveringHostedModels(false);
    }
  }

  async function handleSaveHostedProvider(e: FormEvent) {
    e.preventDefault();
    const draft = hostedProviderDraft;
    const authType = draft.authType || "API_KEY";
    const isVertex = authType === "VERTEX_ADC" || authType === "VERTEX_SERVICE_ACCOUNT";
    const isProviderMode = draft.mode === "provider";
    if (!draft.label.trim()) {
      toast.error("A label is required.");
      return;
    }
    if (isVertex) {
      if (!draft.vertexProjectId.trim()) {
        toast.error("A Vertex AI project ID is required.");
        return;
      }
    } else if (isProviderMode) {
      if (!draft.providerName) {
        toast.error("Pick a provider.");
        return;
      }
      // baseUrl/model are optional here - the server auto-derives baseUrl
      // from providerName and auto-selects a model when left blank (see
      // PlatformHostedLlmProviderService#resolveRequest).
    } else if (!draft.baseUrl.trim() || !draft.model.trim()) {
      toast.error("Label, base URL, and model are all required.");
      return;
    }
    // apiKey is required to CREATE a row for API_KEY and VERTEX_SERVICE_ACCOUNT
    // (same field carries the pasted service-account JSON in that mode) but
    // never required/sent for VERTEX_ADC.
    if (authType !== "VERTEX_ADC" && editingHostedProviderId === null && !draft.apiKey.trim()) {
      toast.error(
        authType === "VERTEX_SERVICE_ACCOUNT"
          ? "Paste the service account JSON key to add a new Vertex AI provider."
          : "An API key is required to add a new hosted provider."
      );
      return;
    }
    setSavingHostedProvider(true);
    try {
      const payload = {
        label: draft.label.trim(),
        baseUrl: !isVertex && draft.baseUrl.trim() ? draft.baseUrl.trim() : null,
        model: draft.model.trim() ? draft.model.trim() : null,
        apiKey: authType === "VERTEX_ADC" ? null : draft.apiKey.trim() ? draft.apiKey.trim() : null,
        enabled: draft.enabled,
        priority: Number(draft.priority) || 100,
        region: draft.region.trim() ? draft.region.trim() : null,
        completionsPath: !isVertex && draft.completionsPath.trim() ? draft.completionsPath.trim() : null,
        providerName: !isVertex && isProviderMode ? draft.providerName : null,
        authType,
        vertexProjectId: isVertex && draft.vertexProjectId.trim() ? draft.vertexProjectId.trim() : null,
        vertexLocation: isVertex ? draft.vertexLocation.trim() || "us-central1" : null,
        zeroCost: !isVertex && Boolean(draft.zeroCost),
        ...hostedRowOverridesToPayload(draft),
      };
      if (editingHostedProviderId === null) {
        const created = await api.createPlatformHostedLlmProvider(payload);
        setHostedProviders((prev) => [...prev, created].sort((a, b) => a.priority - b.priority));
        toast.success(`${created.label} added to the free-tier pool.`);
      } else {
        const updated = await api.updatePlatformHostedLlmProvider(editingHostedProviderId, payload);
        setHostedProviders((prev) =>
          prev.map((row) => (row.id === updated.id ? updated : row)).sort((a, b) => a.priority - b.priority)
        );
        toast.success(`${updated.label} updated.`);
      }
      cancelHostedProviderEdit();
    } catch (err) {
      toast.error((err as Error).message || "Could not save this hosted provider.");
    } finally {
      setSavingHostedProvider(false);
    }
  }

  async function handleToggleHostedProviderEnabled(row: HostedProviderRow) {
    if (togglingHostedProviderId) return;
    setTogglingHostedProviderId(row.id);
    try {
      const updated = await api.updatePlatformHostedLlmProvider(row.id, {
        label: row.label,
        baseUrl: row.baseUrl,
        model: row.model,
        apiKey: null,
        enabled: !row.enabled,
        priority: row.priority,
        region: row.region || null,
        completionsPath: row.completionsPath || null,
        providerName: row.providerName || null,
        authType: row.authType || "API_KEY",
        vertexProjectId: row.vertexProjectId || null,
        vertexLocation: row.vertexLocation || null,
        zeroCost: Boolean(row.zeroCost),
        ...hostedRowOverridesFromRow(row),
      });
      setHostedProviders((prev) => prev.map((r) => (r.id === updated.id ? updated : r)));
      toast.success(`${updated.label} ${updated.enabled ? "enabled" : "disabled"}.`);
    } catch (err) {
      toast.error((err as Error).message || "Could not update this provider.");
    } finally {
      setTogglingHostedProviderId(null);
    }
  }

  async function handleTogglePoolEnabled() {
    if (!poolSettings || poolSettingsBusy) return;
    if (
      poolSettings.enabled &&
      !(await askConfirm(
        "Turn off the platform-hosted LLM pool? Every FREE-plan tenant's chat, Ask AI, and AI workflow generator calls will fail immediately."
      ))
    ) {
      return;
    }
    setPoolSettingsBusy(true);
    try {
      const updated = await api.setHostedLlmPoolEnabled(!poolSettings.enabled);
      setPoolSettings(updated);
      toast.success(`Platform-hosted LLM pool ${updated.enabled ? "enabled" : "disabled"}.`);
    } catch (err) {
      toast.error((err as Error).message || "Could not update the hosted LLM pool switch.");
    } finally {
      setPoolSettingsBusy(false);
    }
  }

  async function handleDeleteHostedProvider(row: HostedProviderRow) {
    if (!(await askConfirm(`Remove "${row.label}" from the free-tier hosted provider pool?`))) return;
    setDeletingHostedProviderId(row.id);
    try {
      await api.deletePlatformHostedLlmProvider(row.id);
      setHostedProviders((prev) => prev.filter((r) => r.id !== row.id));
      if (editingHostedProviderId === row.id) cancelHostedProviderEdit();
      toast.success(`${row.label} removed.`);
    } catch (err) {
      toast.error((err as Error).message || "Could not remove this provider.");
    } finally {
      setDeletingHostedProviderId(null);
    }
  }

  const regionFilteredHostedProviders = useMemo(
    () => hostedProviders.filter((row) => !hostedProviderRegionFilter || row.region === hostedProviderRegionFilter),
    [hostedProviders, hostedProviderRegionFilter]
  );
  const filteredHostedProviders = useMemo(() => {
    const q = hostedQuery.trim().toLowerCase();
    if (!q) return regionFilteredHostedProviders;
    return regionFilteredHostedProviders.filter(
      (row) =>
        row.label?.toLowerCase().includes(q) ||
        row.baseUrl?.toLowerCase().includes(q) ||
        row.model?.toLowerCase().includes(q)
    );
  }, [regionFilteredHostedProviders, hostedQuery]);
  const hostedPagination = usePagination(filteredHostedProviders);

  return {
    hostedProviders,
    setHostedProviders,
    hostedProvidersError,
    setHostedProvidersError,
    hostedProviderRegionFilter,
    setHostedProviderRegionFilter,
    hostedProviderDraft,
    setHostedProviderDraft,
    editingHostedProviderId,
    poolSettings,
    setPoolSettings,
    poolSettingsBusy,
    savingHostedProvider,
    deletingHostedProviderId,
    togglingHostedProviderId,
    hostedQuery,
    setHostedQuery,
    genericProviderModels,
    loadingGenericProviderModels,
    discoveredHostedModels,
    discoveringHostedModels,
    startEditHostedProvider,
    cancelHostedProviderEdit,
    handleDiscoverHostedModels,
    handleSaveHostedProvider,
    handleToggleHostedProviderEnabled,
    handleTogglePoolEnabled,
    handleDeleteHostedProvider,
    hostedPagination,
  };
}

export type HostedPool = ReturnType<typeof useHostedPool>;
