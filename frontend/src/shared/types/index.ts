export interface ApiResponse<T> {
  success: boolean;
  data: T | null;
  error: {
    code: string;
    message: string;
    detail?: string | null;
    suggestion?: string | null;
  } | null;
  meta: {
    requested_at: string;
    total?: number | null;
    returned?: number | null;
    months?: string[] | null;
  };
}
