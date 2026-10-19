# seaweedfs

Bundled single-node [SeaweedFS](https://github.com/seaweedfs/seaweedfs) (Apache-2.0) exposing an
S3-compatible API on port 8333 (`weed server -filer -s3` in one pod, data on one PVC). Generated
credentials in Secret `<release>-seaweedfs` (`accessKeyId`, `secretAccessKey`, kept on uninstall).
Core creates its bucket on first use.

Core only speaks S3 (there is no filesystem storage backend), so object storage is always S3-compatible:
this chart for evaluation and small installs, or any external S3 (AWS S3, GCS interop, R2, Ceph RGW,
MinIO, a SeaweedFS cluster) via `global.objectStorage.*` with `seaweedfs.enabled=false`.
