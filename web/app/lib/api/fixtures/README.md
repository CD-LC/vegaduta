# API fixtures

`settingsCatalog.snapshot.json` is the real `GET /api/settings/catalog` response, written from the assembled Java
catalog. Web tests render the settings screens against it (see `app/admin/runtime-settings/settingsCoverage.test.tsx`)
so a new setting without copy, or a group the UI cannot name, fails here and not in a customer's console.

`SettingsCatalogContractTest` (core/app) fails when this file drifts from the Java catalog. To refresh it after adding or
changing a setting, from `core/`:

```
mvn -o -q -pl app -am test -Dtest=SettingsCatalogContractTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dvegaduta.settings.snapshot.write=true
```
