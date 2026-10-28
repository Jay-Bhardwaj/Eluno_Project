const state = { stores: [], products: { frames: [], lenses: [] }, cart: [], customer: null, prescriptions: [], activeTab: 'frames', preview: null };
const $ = id => document.getElementById(id);
const money = paise => new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR' }).format((paise || 0) / 100);

async function api(path, options = {}) {
  const response = await fetch(path, { headers: { 'Content-Type': 'application/json', ...(options.headers || {}) }, ...options });
  let body; try { body = await response.json(); } catch { body = {}; }
  if (!response.ok) throw new Error(body.error || `Request failed (${response.status})`);
  return body;
}
function setBusy(button, busy, label) { if (!button) return; button.disabled = busy; button.dataset.label ||= button.textContent; button.textContent = busy ? label : button.dataset.label; }
function toast(message) { const t = $('toast'); t.textContent = message; t.classList.remove('hidden'); clearTimeout(toast.timer); toast.timer = setTimeout(() => t.classList.add('hidden'), 3500); }
function showError(message) { const box = $('catalogueMessage'); box.textContent = message; box.classList.remove('hidden'); }
function clearError() { $('catalogueMessage').classList.add('hidden'); }
function selectedStore() { return state.stores.find(x => String(x.id) === $('storeSelect').value); }

async function init() {
  try {
    const [stores, frames, lenses] = await Promise.all([api('/api/stores'), api('/api/products/frames'), api('/api/products/lenses')]);
    state.stores = stores.filter(x => x.active && x.eluno?.kind === 'store');
    state.products.frames = frames; state.products.lenses = lenses;
    $('storeSelect').innerHTML = state.stores.map(x => `<option value="${x.id}">${escapeHtml(x.name)} · ${x.eluno.store_code}</option>`).join('');
    await updateStaff(); renderProducts();
  } catch (error) { showError(`Unable to load the POS catalogue: ${error.message}`); }
}
async function updateStaff() {
  const store = selectedStore(); if (!store) return;
  $('storeName').textContent = store.name;
  try {
    const staff = await api(`/api/staff?store=${encodeURIComponent(store.eluno.store_code)}`);
    $('staffSelect').innerHTML = staff.map(x => `<option value="${x.staff_id}">${escapeHtml(x.name)} · ${x.role.replace('_', ' ')}</option>`).join('');
  } catch (error) { showError(`Unable to load staff: ${error.message}`); }
}
function variants() { return state.products[state.activeTab].flatMap(product => product.variants.map(variant => ({ product, variant }))); }
function renderProducts() {
  const grid = $('productGrid'); const lens = state.activeTab === 'lenses';
  grid.innerHTML = variants().map(({ product, variant }) => {
    const info = variant.eluno || {}; const label = lens ? `${info.lens_type || 'Lens'} · ${info.index || ''} · ${info.coating || ''}` : `${variant.sku} · ${variant.title}`;
    return `<article class="product-card ${lens ? 'lens' : ''}"><div class="product-icon">${lens ? '◌' : '⌐'}</div><h3>${escapeHtml(product.title)}</h3><p>${escapeHtml(label)}</p><div class="product-footer"><span class="price">${money(decimalToPaise(variant.price))}</span><button class="add" type="button" data-id="${variant.id}" data-title="${escapeAttr(product.title)}" data-type="${info.line_type}">Add</button></div></article>`;
  }).join('') || '<p class="empty-cart">No products available.</p>';
  grid.querySelectorAll('.add').forEach(button => button.addEventListener('click', () => addToCart(button)));
}
function addToCart(button) {
  const variantId = Number(button.dataset.id); const pairRef = $('pairRef').value.trim() || '1';
  const item = state.cart.find(x => x.variant_id === variantId && x.pair_ref === pairRef);
  if (item) item.quantity += 1;
  else state.cart.push({ variant_id: variantId, title: button.dataset.title, type: button.dataset.type, quantity: 1, pair_ref: pairRef });
  state.preview = null; renderCart(); toast('Added to cart');
}
function renderCart() {
  $('cartCount').textContent = state.cart.reduce((n, x) => n + x.quantity, 0);
  const area = $('cartItems');
  area.innerHTML = state.cart.length ? state.cart.map((item, index) => `<div class="cart-item"><div><h3>${escapeHtml(item.title)}</h3><p>${escapeHtml(item.type)} · Pair ${escapeHtml(item.pair_ref)} · Qty ${item.quantity}</p></div><div><button class="remove" data-index="${index}" type="button">Remove</button></div></div>`).join('') : '<p class="empty-cart">Your cart is empty.<br>Add a frame or lens to begin.</p>';
  area.querySelectorAll('.remove').forEach(button => button.addEventListener('click', () => { state.cart.splice(Number(button.dataset.index), 1); state.preview = null; renderCart(); }));
  renderSummary();
}
function renderSummary() {
  const totals = state.preview?.totals || {};
  $('summary').innerHTML = `<div><span>Subtotal</span><strong>${money(totals.gross_paise)}</strong></div><div><span>Discount</span><strong>−${money(totals.discount_paise)}</strong></div><div><span>GST included</span><strong>${money(totals.tax_paise)}</strong></div><div class="grand"><span>Total payable</span><strong>${money(totals.total_paise)}</strong></div>`;
  const validation = $('validationArea');
  if (!state.preview) validation.innerHTML = '';
  else if (state.preview.valid) validation.innerHTML = '<div class="validation ok">Cart is valid. Tax and eligible discounts are included in the total.</div>';
  else validation.innerHTML = `<div class="validation"><b>Action needed</b><br>${state.preview.errors.map(escapeHtml).join('<br>')}</div>`;
}
function salePayload() {
  if (!state.customer) throw new Error('Search and select a customer before continuing.');
  if (!state.cart.length) throw new Error('Add at least one item to the cart.');
  const store = selectedStore();
  return { phone: state.customer.phone, location_id: Number(store.id), staff_id: $('staffSelect').value, rx_id: $('rxSelect').value || undefined, payment_method: 'upi', channel: $('channel').value, lines: state.cart.map(({ variant_id, quantity, pair_ref }) => ({ variant_id, quantity, pair_ref })) };
}
async function validateCart() {
  const button = $('previewButton');
  try { clearError(); setBusy(button, true, 'Validating…'); state.preview = await api('/api/checkout/preview', { method: 'POST', body: JSON.stringify(salePayload()) }); renderSummary(); }
  catch (error) { state.preview = null; renderSummary(); $('validationArea').innerHTML = `<div class="validation">${escapeHtml(error.message)}</div>`; }
  finally { setBusy(button, false); }
}
async function checkout() {
  const button = $('checkoutButton');
  try {
    clearError(); setBusy(button, true, 'Processing terminal…');
    const payload = salePayload();
    if (!state.preview || !state.preview.valid) { state.preview = await api('/api/checkout/preview', { method: 'POST', body: JSON.stringify(payload) }); renderSummary(); }
    if (!state.preview.valid) { toast('Please correct cart validation errors.'); return; }
    const result = await api('/api/checkout', { method: 'POST', body: JSON.stringify(payload) }); showInvoice(result);
  } catch (error) { showPaymentError(error.message); }
  finally { setBusy(button, false); }
}
function showInvoice(result) {
  const totals = result.invoice.totals; $('modalContent').innerHTML = `<div class="success-mark">✓</div><p class="eyebrow">PAYMENT CAPTURED</p><h2>Sale complete</h2><p class="muted">The terminal payment and Shopify order were created successfully.</p><div class="invoice-box"><div class="invoice-line"><span>Invoice</span><strong>${escapeHtml(result.invoice_no)}</strong></div><div class="invoice-line"><span>Shopify order</span><strong>#${result.shopify_order_id}</strong></div><div class="invoice-line"><span>Terminal reference</span><strong>${escapeHtml(result.payment_reference)}</strong></div><div class="invoice-line"><span>Total paid</span><strong>${money(totals.total_paise)}</strong></div></div><div class="refund-box"><p class="muted">Need to reverse this sale?</p><button id="refundButton" class="outline" type="button">Issue full refund</button></div>`; openModal(); $('refundButton').addEventListener('click', () => refund(result.shopify_order_id));
}
async function refund(orderId) {
  const button = $('refundButton');
  try { setBusy(button, true, 'Refunding…'); const result = await api(`/api/refunds/${orderId}/full`, { method: 'POST', body: JSON.stringify({ reason_code: 'CUSTOMER_REQUEST' }) }); $('modalContent').innerHTML = `<div class="success-mark">✓</div><p class="eyebrow">REFUND RECORDED</p><h2>Credit note created</h2><div class="invoice-box"><div class="invoice-line"><span>Credit note</span><strong>${escapeHtml(result.credit_note_no)}</strong></div><div class="invoice-line"><span>Refund ID</span><strong>${result.refund_id}</strong></div><div class="invoice-line"><span>Amount returned</span><strong>${money(result.amount_paise)}</strong></div></div><p class="muted">The frame was returned to the billing store. Made-to-order lenses were not restocked.</p>`; }
  catch (error) { toast(`Refund failed: ${error.message}`); }
  finally { setBusy(button, false); }
}
async function lookupCustomer(event) {
  event.preventDefault(); const button = event.submitter; const phone = $('phone').value.trim();
  try { setBusy(button, true, 'Finding…'); const result = await api(`/api/customers/lookup?phone=${encodeURIComponent(phone)}`); state.customer = result.customer; state.prescriptions = result.prescriptions || []; const tags = (result.customer.tags || '').split(',').filter(Boolean); $('customerCard').innerHTML = `<strong>${escapeHtml(result.customer.first_name || '')} ${escapeHtml(result.customer.last_name || '')}</strong><span>${escapeHtml(result.customer.phone)}</span><div>${tags.map(tag => `<span class="tag">${escapeHtml(tag)}</span>`).join('')}</div>`; $('rxLabel').classList.remove('hidden'); $('rxSelect').innerHTML = `<option value="">Latest valid prescription</option>${state.prescriptions.map(rx => `<option value="${rx.rx_id}" ${result.latest_valid_prescription?.rx_id === rx.rx_id ? 'selected' : ''}>${escapeHtml(rx.rx_id)} · valid until ${escapeHtml(rx.valid_until)}</option>`).join('')}`; toast('Customer loaded'); }
  catch (error) { state.customer = null; $('customerCard').textContent = error.message; $('rxLabel').classList.add('hidden'); }
  finally { setBusy(button, false); }
}
async function dayEnd() {
  const store = selectedStore(); const date = new Date().toISOString().slice(0, 10); $('modalContent').innerHTML = `<p class="eyebrow">DAY-END REPORT</p><h2>${escapeHtml(store.name)}</h2><p class="muted">Choose a business date.</p><label>Reporting date<input id="reportDate" type="date" value="${date}"></label><button id="loadReport" class="primary wide" type="button">Load Shopify report</button>`; openModal(); $('loadReport').addEventListener('click', async () => { const b = $('loadReport'); try { setBusy(b, true, 'Loading…'); const report = await api(`/api/reports/day-end?locationId=${store.id}&date=${$('reportDate').value}`); $('modalContent').innerHTML = `<p class="eyebrow">DAY-END REPORT</p><h2>${escapeHtml(store.name)}</h2><p class="muted">${escapeHtml(report.date)} · Shopify source of truth</p><div class="report-grid">${metric('Bills', report.bill_count)}${metric('Gross sales', money(report.gross_sales_paise))}${metric('Discounts', money(report.discounts_paise))}${metric('Tax', money(report.tax_paise))}${metric('Refunds', money(report.refunds_paise))}${metric('Net total', money(report.net_paise))}</div><h3>Payment methods</h3><div class="invoice-box">${Object.entries(report.payment_methods_paise || {}).map(([key, value]) => `<div class="invoice-line"><span>${escapeHtml(key)}</span><strong>${money(value)}</strong></div>`).join('') || '<p class="muted">No payments found.</p>'}</div>`; } catch (error) { toast(`Report failed: ${error.message}`); } finally { setBusy(b, false); } });
}
function metric(name, value) { return `<div class="metric"><span>${name}</span><strong>${value}</strong></div>`; }
function openModal() { $('modal').classList.remove('hidden'); } function closeModal() { $('modal').classList.add('hidden'); }
function clearSale() { state.cart = []; state.preview = null; state.customer = null; state.prescriptions = []; $('customerCard').textContent = 'Search for a customer to begin.'; $('rxLabel').classList.add('hidden'); renderCart(); toast('New sale started'); }
function decimalToPaise(value) { return Math.round(Number(value) * 100); } function escapeHtml(value) { const p = document.createElement('p'); p.textContent = String(value ?? ''); return p.innerHTML; } function escapeAttr(value) { return escapeHtml(value).replaceAll('"', '&quot;'); }
document.addEventListener('DOMContentLoaded', () => { $('storeSelect').addEventListener('change', updateStaff); $('customerForm').addEventListener('submit', lookupCustomer); $('previewButton').addEventListener('click', validateCart); $('checkoutButton').addEventListener('click', checkout); $('clearCart').addEventListener('click', () => { state.cart = []; state.preview = null; renderCart(); }); $('clearSale').addEventListener('click', clearSale); $('reportButton').addEventListener('click', dayEnd); $('closeModal').addEventListener('click', closeModal); $('modal').addEventListener('click', e => { if (e.target === $('modal')) closeModal(); }); document.querySelectorAll('.tab').forEach(tab => tab.addEventListener('click', () => { document.querySelector('.tab.active').classList.remove('active'); tab.classList.add('active'); state.activeTab = tab.dataset.catalogue; renderProducts(); })); init(); });
