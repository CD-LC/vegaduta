"""Analysis endpoints (codebase_rag/analysis.py) - deterministic reads over
the built graph. All read-only, all tenant-scoped, all cheap (no rebuild).
Split out of ``app.main`` so both modules stay under the file-size cap."""

from fastapi import APIRouter, Depends

from .deps import service, tenant
from .tenancy import TenantContext

router = APIRouter()


@router.get("/repos/{repo_id}/map")
def repo_map(
    repo_id: str, budget: int = 16000, top_n: int = 200, ctx: TenantContext = Depends(tenant)
):
    """PageRank-ranked skeleton of the repo - signatures, not bodies. The
    agent's first call: learn the codebase's shape before fetching bodies."""
    return service.repo_map(
        ctx, repo_id, budget=min(max(budget, 1000), 64000), top_n=min(max(top_n, 1), 500)
    )


@router.get("/repos/{repo_id}/symbols")
def find_symbols(
    repo_id: str,
    q: str = "",
    kind: str = "",
    path_prefix: str = "",
    limit: int = 50,
    include_tests: bool = True,
    ctx: TenantContext = Depends(tenant),
):
    """Name-path symbol search (`Auth.login` matches `auth.Auth.login`)."""
    return service.find_symbols(
        ctx,
        repo_id,
        query=q,
        kind=kind,
        path_prefix=path_prefix,
        limit=limit,
        include_tests=include_tests,
    )


@router.get("/repos/{repo_id}/symbols/{qualified_name}")
def symbol_detail(
    repo_id: str,
    qualified_name: str,
    include_body: bool = True,
    ctx: TenantContext = Depends(tenant),
):
    """One symbol: metadata + exact source span (+ body by default)."""
    return service.symbol_detail(ctx, repo_id, qualified_name, include_body=include_body)


@router.get("/repos/{repo_id}/outline")
def file_outline(repo_id: str, path: str, ctx: TenantContext = Depends(tenant)):
    """One file's symbol tree (the Serena get_symbols_overview shape)."""
    return service.file_outline(ctx, repo_id, path)


@router.get("/repos/{repo_id}/references/{symbol}")
def references(repo_id: str, symbol: str, ctx: TenantContext = Depends(tenant)):
    """Every inbound mention: calls + inherits + value/type references."""
    return service.references_of(ctx, repo_id, symbol)


@router.get("/repos/{repo_id}/graph/implementations/{symbol}")
def implementations(
    repo_id: str, symbol: str, depth: int = 4, ctx: TenantContext = Depends(tenant)
):
    """Recursive subclass/implementer tree over `inherits` edges."""
    return service.implementations_of(ctx, repo_id, symbol, depth=min(max(depth, 1), 10))


@router.get("/repos/{repo_id}/graph/path")
def call_path(
    repo_id: str, src: str, dst: str, max_depth: int = 8, ctx: TenantContext = Depends(tenant)
):
    """BFS shortest call chain between two symbols."""
    return service.call_path(ctx, repo_id, src, dst, max_depth=min(max(max_depth, 1), 16))


@router.get("/repos/{repo_id}/dead-code")
def dead_code(repo_id: str, limit: int = 100, ctx: TenantContext = Depends(tenant)):
    """Top-level symbols with zero inbound edges - review candidates."""
    return service.dead_code(ctx, repo_id, limit=limit)


@router.get("/repos/{repo_id}/tests/{symbol}")
def tests_for(repo_id: str, symbol: str, ctx: TenantContext = Depends(tenant)):
    """Test-side linkage: test-file symbols that reach `symbol`."""
    return service.tests_for(ctx, repo_id, symbol)


@router.get("/repos/{repo_id}/api-surface")
def api_surface(repo_id: str, ctx: TenantContext = Depends(tenant)):
    """Extracted HTTP routes (FastAPI/Flask/Express/Spring/Go/Rust)."""
    return service.api_surface(ctx, repo_id)


@router.get("/repos/{repo_id}/hotspots")
def hotspots(repo_id: str, days: int = 90, limit: int = 30, ctx: TenantContext = Depends(tenant)):
    """git churn x size - where bugs concentrate."""
    return service.hotspots(ctx, repo_id, days=min(max(days, 1), 3650), limit=limit)


@router.get("/repos/{repo_id}/import-cycles")
def import_cycles(repo_id: str, ctx: TenantContext = Depends(tenant)):
    """File-level import-graph cycles (SCCs > 1)."""
    return service.import_cycles(ctx, repo_id)
