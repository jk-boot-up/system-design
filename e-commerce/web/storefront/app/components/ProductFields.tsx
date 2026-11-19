export interface ProductValues {
  name?: string;
  description?: string;
  categoryId?: string;
  brand?: string;
  price?: { amount?: string; currency?: string };
  status?: string;
}

/** Fields shared by the new and edit forms; errors are shown next to each field. */
export function ProductFields({ values, categories, errors }: { values?: ProductValues; categories: { id: string; label: string }[]; errors: Record<string, string> }) {
  const err = (name: string) => errors[name] && <span id={`${name}-error`} className="text-sm text-danger">{errors[name]}</span>;
  const aria = (name: string) => ({ 'aria-invalid': Boolean(errors[name]), 'aria-describedby': errors[name] ? `${name}-error` : undefined });
  return (
    <>
      <label className="block"><span className="font-semibold">Name</span>
        <input name="name" required maxLength={200} defaultValue={values?.name} className="field mt-1" {...aria('name')} />{err('name')}</label>
      <label className="block"><span className="font-semibold">Brand</span>
        <input name="brand" required maxLength={100} defaultValue={values?.brand} className="field mt-1" {...aria('brand')} />{err('brand')}</label>
      <label className="block"><span className="font-semibold">Category</span>
        <select name="categoryId" required defaultValue={values?.categoryId} className="field mt-1" {...aria('categoryId')}>
          <option value="">Choose…</option>
          {categories.map((c) => <option key={c.id} value={c.id}>{c.label}</option>)}
        </select>{err('categoryId')}</label>
      <div className="flex gap-3">
        <label className="block flex-1"><span className="font-semibold">Price</span>
          <input name="amount" required inputMode="decimal" pattern="\d{1,7}(\.\d{1,2})?" defaultValue={values?.price?.amount} className="field mt-1" {...aria('amount')} />{err('amount')}</label>
        <label className="block"><span className="font-semibold">Currency</span>
          <select name="currency" defaultValue={values?.price?.currency ?? 'EUR'} className="field mt-1"><option>EUR</option></select></label>
      </div>
      <label className="block"><span className="font-semibold">Status</span>
        <select name="status" defaultValue={values?.status ?? 'DRAFT'} className="field mt-1">
          <option value="DRAFT">Draft (hidden)</option><option value="ACTIVE">Active (for sale)</option><option value="ARCHIVED">Archived</option>
        </select></label>
      <label className="block"><span className="font-semibold">Description</span>
        <textarea name="description" maxLength={10000} rows={6} defaultValue={values?.description} className="field mt-1" /></label>
    </>
  );
}
