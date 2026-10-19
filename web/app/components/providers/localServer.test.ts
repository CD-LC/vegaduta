import { describe, expect, it } from "vitest";
import { LOCAL_SERVER_PRESETS, allowListEntryFor, localServerUrl } from "./localServer";
import { IMPLEMENTED_PROVIDERS, MULTI_FIELD_PROVIDERS, PROVIDER_INFO, encodeMultiFieldCredential } from "./providerCatalog";

describe("LOCAL_OPENAI card", () => {
  it("is a selectable, implemented provider with card info", () => {
    expect(IMPLEMENTED_PROVIDERS.has("LOCAL_OPENAI")).toBe(true);
    expect(PROVIDER_INFO.LOCAL_OPENAI.labelKey).toBe("localOpenaiLabel");
  });

  it("encodes the credential with the backend record's component names, omitting a blank key", () => {
    const fields = MULTI_FIELD_PROVIDERS.LOCAL_OPENAI!;
    expect(fields.map((f) => f.key)).toEqual(["baseUrl", "apiKey"]);
    expect(fields.find((f) => f.key === "baseUrl")!.required).toBe(true);
    expect(fields.find((f) => f.key === "apiKey")!.required).toBe(false);
    expect(JSON.parse(encodeMultiFieldCredential(fields, { baseUrl: " http://localhost:8000/v1 ", apiKey: " " }))).toEqual({
      baseUrl: "http://localhost:8000/v1",
    });
    expect(JSON.parse(encodeMultiFieldCredential(fields, { baseUrl: "http://h:1/v1", apiKey: "sk-1" }))).toEqual({
      baseUrl: "http://h:1/v1",
      apiKey: "sk-1",
    });
  });
});

describe("local server presets", () => {
  it("fills the documented default port of each server, with the /v1 suffix", () => {
    const urls = Object.fromEntries(LOCAL_SERVER_PRESETS.map((p) => [p.id, localServerUrl(p, false)]));
    expect(urls).toEqual({
      vllm: "http://localhost:8000/v1",
      llamacpp: "http://localhost:8080/v1",
      lmstudio: "http://localhost:1234/v1",
      jan: "http://localhost:1337/v1",
      localai: "http://localhost:8080/v1",
    });
  });

  it("points at the host from inside Docker", () => {
    expect(localServerUrl(LOCAL_SERVER_PRESETS[0], true)).toBe("http://host.docker.internal:8000/v1");
  });
});

describe("allowListEntryFor", () => {
  it("names the host:port entry the operator must allow, with the default port spelled out", () => {
    expect(allowListEntryFor("http://localhost:8000/v1")).toBe("localhost:8000");
    expect(allowListEntryFor(" http://GPU-BOX.lan/v1 ")).toBe("gpu-box.lan:80");
    expect(allowListEntryFor("https://llm.example.com")).toBe("llm.example.com:443");
    expect(allowListEntryFor("http://host.docker.internal:1234/v1")).toBe("host.docker.internal:1234");
  });

  it("is null for something that is not an http(s) URL", () => {
    expect(allowListEntryFor("")).toBeNull();
    expect(allowListEntryFor("not a url")).toBeNull();
    expect(allowListEntryFor("ftp://h:21")).toBeNull();
  });
});
