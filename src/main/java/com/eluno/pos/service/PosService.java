package com.eluno.pos.service;

import com.eluno.pos.client.ApiClient;
import com.eluno.pos.client.ApiClient.ApiException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Service;
import java.io.*;
import java.math.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;

@Service
public class PosService {
  private final ApiClient api;
  private final ObjectMapper json;
  private final JsonNode offers, rates;
  private final LocalDate businessDate;
  private final Path state = Path.of("pos-state.json");

  public PosService(ApiClient a, ObjectMapper j, @Value("${eluno.business-date:}") String configured)
      throws IOException {
    api = a;
    json = j;
    offers = j.readTree(Path.of("starter-kit/data/offers.json").toFile());
    rates = j.readTree(Path.of("starter-kit/data/tax_rates.json").toFile()).path("rates");
    businessDate = configured == null || configured.isBlank() ? LocalDate.now() : LocalDate.parse(configured);
  }

  public JsonNode customer(String phone) {
    ObjectNode out = api.object();
    JsonNode c = api.getShopify("/customers/search.json?query=phone:" + phone.replace("+", "%2B")).path("customers");
    if (c.isEmpty())
      throw fail(404, "Customer not found");
    JsonNode rx = api.getSmt("/smt/prescriptions?phone=" + phone.replace("+", "%2B")).path("prescriptions");
    out.set("customer", c.get(0));
    out.set("prescriptions", rx);
    for (JsonNode r : rx)
      if (validRx(r)) {
        out.set("latest_valid_prescription", r);
        break;
      }
    return out;
  }

  public JsonNode products(String type) {
    return api.getShopify("/products.json?product_type=" + type + "&limit=250").path("products");
  }

  public JsonNode stores() {
    return api.getShopify("/locations.json").path("locations");
  }

  public JsonNode staffForStore(String storeCode) {
    return api.getSmt("/smt/staff?store=" + storeCode).path("staff");
  }

  public JsonNode preview(JsonNode request) {
    return calculate(request, false);
  }

  public JsonNode checkout(JsonNode request) {
    ObjectNode invoice = (ObjectNode) calculate(request, true);
    String no = nextInvoice(invoice.path("store_code").asText(), invoice.path("invoice_prefix").asText());
    save(no, "VALIDATED", request, null, null, null);
    long total = invoice.path("totals").path("total_paise").asLong();
    save(no, "PAYMENT_PENDING", request, null, null, null);
    ObjectNode charge = api.object();
    charge.put("amount_paise", total);
    charge.put("method", request.path("payment_method").asText("upi"));
    charge.put("order_ref", no);
    JsonNode payment = api.postTerminal("/terminal/charges", charge);
    save(no, "PAYMENT_CAPTURED", request, payment.path("charge_id").asText(), null, null);
    ObjectNode order = shopifyOrder(request, invoice, no, payment.path("charge_id").asText());
    try {
      JsonNode created = api.postShopify("/orders.json", api.object().set("order", order));
      save(no, "COMPLETED", request, payment.path("charge_id").asText(), created.path("order").path("id").asLong(),
          null);
      ObjectNode result = api.object();
      result.put("invoice_no", no);
      result.put("payment_reference", payment.path("charge_id").asText());
      result.put("shopify_order_id", created.path("order").path("id").asLong());
      result.set("invoice", invoice);
      return result;
    } catch (ApiException e) {
      save(no, "ORDER_RECOVERY_REQUIRED", request, payment.path("charge_id").asText(), null, e.getMessage());
      throw e;
    }
  }

  public JsonNode refund(long id, String reason) {
    JsonNode order = api.getShopify("/orders/" + id + ".json").path("order");
    JsonNode sale = StreamSupport.stream(order.path("transactions").spliterator(), false)
        .filter(x -> "sale".equals(x.path("kind").asText()) && "success".equals(x.path("status").asText())).findFirst()
        .orElseThrow(() -> fail(422, "Original sale transaction not found"));
    ArrayNode lines = api.array();
    for (JsonNode li : order.path("line_items")) {
      ObjectNode r = api.object();
      r.put("line_item_id", li.path("id").asLong());
      r.put("quantity", li.path("quantity").asInt());
      if ("frame".equals(property(li, "line_type"))) {
        r.put("restock_type", "return");
        r.put("location_id", order.path("location_id").asLong());
      } else
        r.put("restock_type", "no_restock");
      lines.add(r);
    }
    ObjectNode wrap = api.object();
    wrap.set("refund_line_items", lines);
    JsonNode calc = api.postShopify("/orders/" + id + "/refunds/calculate.json", api.object().set("refund", wrap))
        .path("refund");
    long amount = 0;
    for (JsonNode l : calc.path("refund_line_items"))
      amount += paise(l.path("subtotal").asText()) + paise(l.path("total_tax").asText());
    ObjectNode terminal = api.object();
    terminal.put("charge_id", sale.path("authorization").asText());
    terminal.put("amount_paise", amount);
    terminal.put("reason", reason);
    api.postTerminal("/terminal/refunds", terminal);
    ObjectNode refund = api.object();
    refund.put("note", reason);
    refund.set("refund_line_items", lines);
    ArrayNode tx = api.array();
    ObjectNode t = api.object();
    t.put("kind", "refund");
    t.put("gateway", "terminal");
    t.put("amount", money(amount));
    t.put("parent_id", sale.path("id").asLong());
    tx.add(t);
    refund.set("transactions", tx);
    ArrayNode notes = api.array();
    ObjectNode note = api.object();
    note.put("name", "credit_note_no");
    note.put("value", property(order, "invoice_no") + "/CN");
    notes.add(note);
    refund.set("note_attributes", notes);
    JsonNode done = api.postShopify("/orders/" + id + "/refunds.json", api.object().set("refund", refund));
    ObjectNode out = api.object();
    out.put("order_id", id);
    out.put("refund_id", done.path("refund").path("id").asLong());
    out.put("amount_paise", amount);
    out.put("credit_note_no", note.path("value").asText());
    return out;
  }

  public JsonNode report(long location, String day) {
    JsonNode os = api.getShopify("/orders.json?location_id=" + location + "&status=any&limit=250&created_at_min=" + day
        + "T00:00:00%2B05:30&created_at_max=" + day + "T23:59:59%2B05:30").path("orders");
    long gross = 0, disc = 0, tax = 0, refund = 0;
    ObjectNode methods = api.object();
    for (JsonNode o : os) {
      gross += paise(o.path("subtotal_price").asText());
      disc += paise(o.path("total_discounts").asText());
      tax += paise(o.path("total_tax").asText());
      String m = property(o, "payment_method");
      methods.put(m, methods.path(m).asLong() + paise(o.path("total_price").asText()));
      for (JsonNode r : o.path("refunds"))
        for (JsonNode t : r.path("transactions"))
          refund += paise(t.path("amount").asText());
    }
    ObjectNode out = api.object();
    out.put("location_id", location);
    out.put("date", day);
    out.put("bill_count", os.size());
    out.put("gross_sales_paise", gross);
    out.put("discounts_paise", disc);
    out.put("tax_paise", tax);
    out.put("taxable_paise", gross - disc - tax);
    out.put("refunds_paise", refund);
    out.put("net_paise", gross - disc - refund);
    out.set("payment_methods_paise", methods);
    return out;
  }

  private ObjectNode calculate(JsonNode req, boolean strict) {
    JsonNode lookup = customer(req.path("phone").asText());
    JsonNode loc = location(req.path("location_id").asLong());
    JsonNode staff = staff(loc.path("eluno").path("store_code").asText(), req.path("staff_id").asText());
    JsonNode rx = selectRx(lookup.path("prescriptions"), req.path("rx_id").asText());
    ArrayNode lines = api.array(), errors = api.array();
    for (JsonNode raw : req.path("lines")) {
      JsonNode v = api.getShopify("/variants/" + raw.path("variant_id").asLong() + ".json").path("variant");
      JsonNode p = api.getShopify("/products/" + v.path("product_id").asLong() + ".json").path("product");
      if (!"active".equals(p.path("status").asText())) {
        errors.add(v.path("sku").asText() + " is not active");
        continue;
      }
      String type = v.path("eluno").path("line_type").asText();
      if ("frame".equals(type) && !inStock(v, loc, raw.path("quantity").asInt(1)))
        errors.add(v.path("sku").asText() + " is unavailable at this store");
      if ("lens".equals(type)) {
        if (rx == null)
          errors.add("No valid prescription available for lens");
        else
          validateLens(v, rx, errors);
      }
      ObjectNode line = api.object();
      line.set("variant", v);
      line.put("title", p.path("title").asText());
      line.put("type", type);
      line.put("pair_ref", raw.path("pair_ref").asText("1"));
      line.put("quantity", raw.path("quantity").asInt(1));
      line.put("gross_paise", paise(v.path("price").asText()) * line.path("quantity").asInt());
      line.put("discount_paise", 0);
      lines.add(line);
    }
    if (strict && !errors.isEmpty())
      throw fail(422, errors.get(0).asText());
    applyOffers(lines, lookup.path("customer"), staff, req);
    long gross = 0, discount = 0, taxable = 0, tax = 0, total = 0;
    for (JsonNode n : lines) {
      ObjectNode l = (ObjectNode) n;
      long net = l.path("gross_paise").asLong() - l.path("discount_paise").asLong();
      int rate = taxRate(l.path("variant").path("eluno").path("hsn").asText());
      long[] t = tax(net, rate);
      l.put("net_paise", net);
      l.put("taxable_paise", t[0]);
      l.put("cgst_paise", t[1]);
      l.put("sgst_paise", t[2]);
      l.put("rate_bps", rate);
      gross += l.path("gross_paise").asLong();
      discount += l.path("discount_paise").asLong();
      taxable += t[0];
      tax += t[1] + t[2];
      total += net;
    }
    ObjectNode out = api.object();
    out.put("valid", errors.isEmpty());
    out.set("errors", errors);
    out.set("lines", lines);
    ObjectNode ts = api.object();
    ts.put("gross_paise", gross);
    ts.put("discount_paise", discount);
    ts.put("taxable_paise", taxable);
    ts.put("tax_paise", tax);
    ts.put("total_paise", total);
    out.set("totals", ts);
    out.put("store_code", loc.path("eluno").path("store_code").asText());
    out.put("invoice_prefix", loc.path("eluno").path("invoice_prefix").asText());
    out.put("prescription_id", rx == null ? "" : rx.path("rx_id").asText());
    return out;
  }

  private JsonNode location(long id) {
    for (JsonNode x : api.getShopify("/locations.json").path("locations"))
      if (x.path("id").asLong() == id && x.path("active").asBoolean())
        return x;
    throw fail(422, "Active billing location not found");
  }

  private JsonNode staff(String store, String id) {
    for (JsonNode x : api.getSmt("/smt/staff?store=" + store).path("staff"))
      if (id.equals(x.path("staff_id").asText()))
        return x;
    throw fail(422, "Staff member is not assigned to this store");
  }

  private JsonNode selectRx(JsonNode xs, String id) {
    for (JsonNode x : xs)
      if ((id.isBlank() || id.equals(x.path("rx_id").asText())) && validRx(x))
        return x;
    return null;
  }

  private boolean validRx(JsonNode r) {
    try {
      if (LocalDate.parse(r.path("valid_until").asText()).isBefore(businessDate))
        return false;
      for (String eye : List.of("od", "os")) {
        JsonNode e = r.path(eye);
        if (e.path("sph").isMissingNode() || e.path("cyl").isMissingNode() || e.path("sph").isNull()
            || e.path("cyl").isNull())
          return false;
        if (e.path("cyl").decimalValue().compareTo(BigDecimal.ZERO) != 0
            && (e.path("axis").isNull() || e.path("axis").asInt() < 0 || e.path("axis").asInt() > 180))
          return false;
      }
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private boolean inStock(JsonNode v, JsonNode loc, int qty) {
    JsonNode a = api.getShopify("/inventory_levels.json?inventory_item_ids=" + v.path("inventory_item_id").asLong()
        + "&location_ids=" + loc.path("id").asLong()).path("inventory_levels");
    return !a.isEmpty() && a.get(0).path("available").asInt() >= qty;
  }

  private void validateLens(JsonNode v, JsonNode rx, ArrayNode errors) {
    JsonNode lim = v.path("eluno").path("rx_limits");
    for (String eye : List.of("od", "os")) {
      JsonNode e = rx.path(eye);
      String name = eye.equals("od") ? "right" : "left";
      BigDecimal sph = e.path("sph").decimalValue(), cyl = e.path("cyl").decimalValue();
      if (sph.compareTo(lim.path("sph_min").decimalValue()) < 0
          || sph.compareTo(lim.path("sph_max").decimalValue()) > 0)
        errors.add("Selected lens does not support the " + name + "-eye spherical power.");
      if (cyl.abs().compareTo(lim.path("cyl_abs_max").decimalValue()) > 0)
        errors.add("Selected lens does not support the " + name + "-eye cylindrical power.");
      if (cyl.compareTo(BigDecimal.ZERO) != 0 && e.path("axis").asInt() % 5 != 0)
        errors.add(name + " eye axis must be on the five-degree grid.");
      if (lim.path("add_required").asBoolean() && e.path("add").isNull())
        errors.add("Selected lens requires ADD for the " + name + " eye.");
      if (!e.path("add").isNull() && (e.path("add").decimalValue().compareTo(lim.path("add_min").decimalValue()) < 0
          || e.path("add").decimalValue().compareTo(lim.path("add_max").decimalValue()) > 0))
        errors.add("Selected lens does not support the " + name + "-eye ADD.");
    }
  }

  private int taxRate(String hsn) {
    String d = businessDate.toString();
    for (JsonNode r : rates)
      if (hsn.equals(r.path("hsn").asText()) && d.compareTo(r.path("effective_from").asText()) >= 0
          && (!r.has("effective_to") || d.compareTo(r.path("effective_to").asText()) <= 0))
        return r.path("rate_bps").asInt();
    throw fail(422, "No effective GST rate for HSN " + hsn);
  }

  private long[] tax(long g, int b) {
    long taxable = BigDecimal.valueOf(g).multiply(BigDecimal.valueOf(10000))
        .divide(BigDecimal.valueOf(10000L + b), 0, RoundingMode.HALF_UP).longValueExact();
    long t = g - taxable,
        c = BigDecimal.valueOf(t).divide(BigDecimal.valueOf(2), 0, RoundingMode.HALF_UP).longValueExact();
    return new long[] { taxable, c, t - c };
  }

  private void applyOffers(ArrayNode lines, JsonNode customer, JsonNode staff, JsonNode req) {
    List<JsonNode> candidates = new ArrayList<>();
    String d = businessDate.toString();
    Set<String> tags = Set.of(customer.path("tags").asText().split(","));
    for (JsonNode o : offers) {
      JsonNode r = o.path("rule"), c = r.path("condition"), need = r.path("requires");
      if (!o.path("active").asBoolean() || d.compareTo(o.path("valid_from").asText()) < 0
          || d.compareTo(o.path("valid_to").asText()) > 0)
        continue;
      if (c.has("customer_tag") && !tags.contains(c.path("customer_tag").asText()))
        continue;
      if (c.path("min_pairs_in_cart").asInt() > pairCount(lines))
        continue;
      if (need.has("role") && (!need.path("role").asText().equals(staff.path("role").asText())
          || !staff.path("can_override_discount").asBoolean() || req.path("reason_code").asText().isBlank()))
        continue;
      candidates.add(o);
    }
    List<List<JsonNode>> choices = new ArrayList<>();
    choices.add(candidates.stream().filter(o -> o.path("stackable").asBoolean()).toList());
    for (JsonNode o : candidates)
      if (!o.path("stackable").asBoolean())
        choices.add(List.of(o));
    List<JsonNode> best = choices.stream().max(Comparator.comparingLong(x -> estimate(lines, x, req)))
        .orElse(List.of());
    for (JsonNode o : best)
      discount(lines, o, req);
  }

  private int pairCount(ArrayNode ls) {
    return (int) StreamSupport.stream(ls.spliterator(), false).map(x -> x.path("pair_ref").asText()).distinct().count();
  }

  private long estimate(ArrayNode ls, List<JsonNode> os, JsonNode req) {
    long n = 0;
    for (JsonNode o : os) {
      for (JsonNode l : targets(ls, o)) {
        JsonNode d = o.path("rule").path("discount");
        n += d.path("type").asText().equals("percent")
            ? l.path("gross_paise").asLong() * d.path("value_bps").asLong() / 10000
            : d.path("value_paise").asLong();
      }
    }
    return n;
  }

  private List<ObjectNode> targets(ArrayNode ls, JsonNode o) {
    JsonNode r = o.path("rule"), c = r.path("condition");
    List<ObjectNode> out = new ArrayList<>();
    for (JsonNode n : ls) {
      ObjectNode l = (ObjectNode) n;
      String applies = r.path("applies_to").asText();
      if (!applies.equals("cart") && !applies.equals(l.path("type").asText()) && !applies.equals("pair"))
        continue;
      JsonNode v = l.path("variant").path("eluno");
      if (c.has("lens_type") && !c.path("lens_type").asText().equals(v.path("lens_type").asText()))
        continue;
      if (c.has("index_min") && v.path("index").asDouble() < c.path("index_min").asDouble())
        continue;
      if (c.has("coating") && !c.path("coating").asText().equals(v.path("coating").asText()))
        continue;
      out.add(l);
    }
    return out;
  }

  private void discount(ArrayNode ls, JsonNode o, JsonNode req) {
    List<ObjectNode> ts = targets(ls, o);
    if (ts.isEmpty())
      return;
    JsonNode d = o.path("rule").path("discount");
    if ("lowest_priced_lens_line".equals(d.path("on").asText()))
      ts = List.of(ts.stream().min(Comparator.comparingLong(x -> x.path("gross_paise").asLong())).get());
    long gross = ts.stream().mapToLong(x -> x.path("gross_paise").asLong()).sum(),
        rate = "MANAGER_OVERRIDE".equals(o.path("offer_id").asText())
            ? Math.min(req.path("manager_discount_bps").asLong(), d.path("value_bps_max").asLong())
            : d.path("value_bps").asLong(),
        amount = "percent".equals(d.path("type").asText()) ? gross * rate / 10000 : d.path("value_paise").asLong();
    amount = Math.min(amount, o.path("max_discount_paise").asLong(amount));
    long used = 0;
    for (int i = 0; i < ts.size(); i++) {
      ObjectNode l = ts.get(i);
      long part = i == ts.size() - 1 ? amount - used : amount * l.path("gross_paise").asLong() / gross;
      used += part;
      l.put("discount_paise", Math.min(l.path("gross_paise").asLong(), l.path("discount_paise").asLong() + part));
    }
  }

  private ObjectNode shopifyOrder(JsonNode req, ObjectNode invoice, String no, String charge) {
    ObjectNode o = api.object();
    o.put("taxes_included", true);
    o.put("location_id", req.path("location_id").asLong());
    o.put("inventory_behaviour", "decrement_obeying_policy");
    o.put("source_name", "eluno-pos");
    o.set("customer",
        api.object().put("id", customer(req.path("phone").asText()).path("customer").path("id").asLong()));
    ArrayNode notes = api.array();
    for (String[] x : List.of(new String[] { "invoice_no", no },
        new String[] { "store_code", invoice.path("store_code").asText() },
        new String[] { "staff_id", req.path("staff_id").asText() },
        new String[] { "payment_method", req.path("payment_method").asText() },
        new String[] { "channel", req.path("channel").asText("walk_in") })) {
      ObjectNode n = api.object();
      n.put("name", x[0]);
      n.put("value", x[1]);
      notes.add(n);
    }
    o.set("note_attributes", notes);
    ArrayNode lis = api.array();
    for (JsonNode line : invoice.path("lines")) {
      ObjectNode li = api.object();
      JsonNode v = line.path("variant");
      li.put("variant_id", v.path("id").asLong());
      li.put("quantity", line.path("quantity").asInt());
      li.put("price", money(line.path("gross_paise").asLong() / line.path("quantity").asLong()));
      li.put("total_discount", money(line.path("discount_paise").asLong()));
      ArrayNode taxes = api.array();
      taxes.add(api.object().put("title", "CGST").put("rate", line.path("rate_bps").asDouble() / 20000).put("price",
          money(line.path("cgst_paise").asLong())));
      taxes.add(api.object().put("title", "SGST").put("rate", line.path("rate_bps").asDouble() / 20000).put("price",
          money(line.path("sgst_paise").asLong())));
      li.set("tax_lines", taxes);
      ArrayNode props = api.array();
      props.add(propertyNode("line_type", line.path("type").asText()));
      props.add(propertyNode("hsn", v.path("eluno").path("hsn").asText()));
      props.add(propertyNode("pair_ref", line.path("pair_ref").asText()));
      if ("lens".equals(line.path("type").asText())) {
        props.add(propertyNode("rx_id", invoice.path("prescription_id").asText()));
        JsonNode e = v.path("eluno");
        props.add(propertyNode("lens_config",
            e.path("lens_type").asText() + "/" + e.path("index").asText() + "/" + e.path("coating").asText()));
      }
      li.set("properties", props);
      if (line.path("discount_paise").asLong() > 0)
        li.set("discount_allocations", api.array().add(api.object()
            .put("amount", money(line.path("discount_paise").asLong())).put("discount_application_index", 0)));
      lis.add(li);
    }
    o.set("line_items", lis);
    ArrayNode tx = api.array();
    tx.add(api.object().put("kind", "sale").put("status", "success").put("gateway", "payment_terminal")
        .put("amount", money(invoice.path("totals").path("total_paise").asLong())).put("authorization", charge));
    o.set("transactions", tx);
    return o;
  }

  private ObjectNode propertyNode(String n, String v) {
    return api.object().put("name", n).put("value", v);
  }

  private String property(JsonNode x, String name) {
    for (JsonNode n : x.path("note_attributes"))
      if (name.equals(n.path("name").asText()))
        return n.path("value").asText();
    for (JsonNode n : x.path("properties"))
      if (name.equals(n.path("name").asText()))
        return n.path("value").asText();
    return "unknown";
  }

  private synchronized String nextInvoice(String store, String prefix) {
    try {
      ObjectNode root = Files.exists(state) ? (ObjectNode) json.readTree(state.toFile()) : api.object();
      long next = root.path(store).path("next").asLong(1);
      ObjectNode value = api.object();
      value.put("prefix", prefix);
      value.put("next", next + 1);
      root.set(store, value);
      json.writerWithDefaultPrettyPrinter().writeValue(state.toFile(), root);
      return prefix + String.format("%05d", next);
    } catch (IOException e) {
      throw fail(500, "Cannot persist invoice series");
    }
  }

  private synchronized void save(String no, String status, JsonNode request, String charge, Long order, String error) {
    try {
      Path p = Path.of("checkout-state.json");
      ObjectNode r = Files.exists(p) ? (ObjectNode) json.readTree(p.toFile()) : api.object();
      ObjectNode x = api.object();
      x.put("state", status);
      x.set("request", request);
      if (charge != null)
        x.put("charge_id", charge);
      if (order != null)
        x.put("shopify_order_id", order);
      if (error != null)
        x.put("error", error);
      r.set(no, x);
      json.writerWithDefaultPrettyPrinter().writeValue(p.toFile(), r);
    } catch (IOException e) {
      throw fail(500, "Cannot persist checkout state");
    }
  }

  private static long paise(String s) {
    return new BigDecimal(s).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
  }

  private static String money(long p) {
    return String.format("%d.%02d", p / 100, p % 100);
  }

  private static ApiException fail(int s, String m) {
    return new ApiException(s, m);
  }
}
