# Video plan

Plans and progress: [design plan](DESIGN-PLAN.md) · [implementation plan](IMPLEMENTATION-PLAN.md) · [status](../STATUS.md)

How the e-commerce platform is taught on video: a set of short videos, one per topic, and one long video that covers
the entire system. This document is the plan; nothing in it is built yet.

**Files are prepared, never uploaded.** The work ends with the video files and one upload sheet per video. Uploading,
scheduling and publishing are done by the author, by hand.

## 1. What is made

| Output | Count | Length | Purpose |
|---|---:|---|---|
| Short videos | 25 | 5 to 12 min each | One topic each; found by search; watched in playlist order |
| Long video | 1 | about 3 h | The entire system in one sitting, with chapters |

About 26 uploads in all. The short videos are the source of truth: the long video is assembled from them by a script
and is never scripted or narrated separately, so a fix is made once and reaches both.

## 2. The short videos

Uploaded in this order. Each playlist lists its videos in the order below.

### 2.0 Overview (first video of playlist A)

| # | Video | Built from |
|---:|---|---|
| 0 | The e-commerce platform: architecture, request path and event flow | walkthrough scenario `tour`, [high-level design](01-high-level-design.md), [system diagrams](11-system-module-service-diagrams.md) |

### 2.1 Playlist A: System walkthrough

One video per scenario of the [system walkthrough](walkthrough/index.html), told with its steps and diagrams.

| # | Video | Walkthrough scenario | Also uses |
|---:|---|---|---|
| A1 | Browse and search | `browse` | catalog and search designs, [database design](04-database-design.md) |
| A2 | Add to cart | `cart` | cart design, Redis keys |
| A3 | Checkout that succeeds | `checkout` | [sequence diagrams](05-sequence-diagrams.md), saga and outbox |
| A4 | Out of stock | `oos` | [flow diagrams](06-flow-diagrams.md), reservation states |
| A5 | Payment declined | `declined` | [state diagrams](07-state-diagrams.md) |
| A6 | Payment timeout | `timeout` | saga recovery, idempotency |
| A7 | Cancellation and refund | `cancel` | compensation steps |
| A8 | Event fan-out | `fanout` | [event catalogue](10-event-catalogue.md) |
| A9 | Flash sale | `flash` | [capacity model](14-capacity-and-performance.md), hot SKU |
| A10 | A dependency fails | `failure` | degrade instead of falling over: timeouts, circuit breakers, retries |

### 2.2 Playlist B: Services

One video per service, from its own `services/<service>/design/README.md`: what it owns, its data, its APIs and
events, the decisions behind it (with their ADRs) and a short tour of its code.

| # | Service | Key ideas |
|---:|---|---|
| B1 | API gateway and edge | NGINX edge, WAF, routing, rate limits, token checks |
| B2 | Catalog | cache-aside with jitter and single-flight, PgBouncer |
| B3 | Search | OpenSearch indexing from events |
| B4 | Cart | Redis, guest and signed-in carts, current prices with a circuit breaker |
| B5 | Inventory | reservations, expiry, hot SKUs |
| B6 | Order | checkout saga, transactional outbox |
| B7 | Payment | idempotent charges, timeouts, refunds |
| B8 | Review | Cassandra tables shaped per query, verified badge, resumable posting |
| B9 | Notification (Go) | Kafka to RabbitMQ, sent log in Redis, delayed retries, dead letters |
| B10 | Recommendation (Python) | bought-together counts in Redis sorted sets, exactly-once counting with Lua |
| B11 | Analytics | ClickHouse ingestion from order and payment events |

### 2.3 Playlist C: Operating it

| # | Video | Built from |
|---:|---|---|
| C1 | Observability and SLOs | [observability and SLOs](13-observability-and-slos.md): one trace across the outbox, burn-rate alerts |
| C2 | Security and the supply chain | [security design](12-security-design.md): service identity, SBOM, signing, Kyverno, WAF |
| C3 | Resilience and runbooks | [runbooks](15-runbooks.md): DLQ replay, stuck sagas, failover, cache stampede |

## 3. The long video

*The E-commerce Platform: Full System Design Course*, about 3 hours.

- **Assembled, not recorded:** a script joins the final short videos in upload order (0, A1 to A10, B1 to B11, C1 to
  C3).
- **Each part enters through its body.** Every short video is rendered as a full video (opening, credit, closing) and
  as a *part* (body only). The long video uses the parts, so the credit and the like-and-subscribe closing appear
  once, not 25 times.
- **Its own frame:** an intro of about 2 minutes (what the course covers, how the system fits together, the three
  sections), a title card with one spoken line before each part, a section card before each playlist's parts, and a
  closing summary.
- **Captions:** the parts' `.srt` files are merged, each shifted by its part's start time, together with the captions
  of the intro, cards and summary.
- **Chapters:** one per part, timed from the merged captions; the description lists them, starting at `00:00`.
- **Rebuilt whenever a short video changes**, then checked again in full: sound, chapter times and captions against
  the picture.
- **Made last**, after every short video is final.

## 4. How each video is made

The same pipeline and rules as the security repository (`jk-boot-up/security`, `docs/beginner-plan.md` sections 6
and 8, and its video teaching standard):

- **Tooling:** videokit (Piper narration, slides, video, captions).
- **Voice:** Piper `en_US-amy-medium`, speed 0.8, −16 LUFS, peaks limited to −1.5 dBFS.
- **Picture:** slides laid out at 1920×1080 and encoded at 3840×2160 (H.264, CRF 18, BT.709); readable text sizes.
- **Content:** diagrams and walkthrough steps with real values (order IDs, SKUs, amounts, topic names), input and
  result shown apart, every acronym spelled out, a short tour of the code where it helps.
- **Narration:** third-person credit ("This video is presented by Jayasekhar Konduru."); "ID" spoken "I D"; the
  closing never names the next video.
- **Per video:** `.mp4`, `.srt`, a 1280×720 thumbnail, and its upload sheet.

## 5. Upload sheets

One sheet per video, prepared and never acted on:

- title (at most 100 characters), description (first sentence names and defines the topic; no `http://` or
  `https://`; no `<` or `>`), tags including the author's name and the repository;
- chapters timed from the video's own `.srt`, starting at `00:00`, at least three;
- the playlist and the video's position in it;
- for the long video, its chapter list; for each short video, a line naming the long video as the full course;
- settings: Unlisted first, then Public after the quality check; not made for kids; category Science & Technology;
  English; captions from the `.srt`.

Sheets are written to `docs/youtube.md` beside the videos, with one index for the whole set.

## 6. Order of work

1. **Before any video:** the checks that need more than 4 GB of Docker memory ([status](../STATUS.md), "Resuming on
   another machine"), so the videos show a system with no open gaps.
2. **Scripts first:** write every video's script and review it for technical accuracy against the code and design
   documents. Mistakes are cheapest here, before anything is rendered.
3. **The overview first, rendered and checked with the author;** it fixes the style for the rest.
4. **Playlist A, then B, then C**, each video rendered, checked (sound, captions against slides, length, thumbnail)
   and its sheet written.
5. **The long video last**, assembled from the final parts and checked in full.
6. **Hand-over:** every file and sheet committed; the author uploads in the order of section 2, then the long video.

## 7. Done when

- 25 short videos and 1 long video, each with `.mp4`, `.srt`, thumbnail and upload sheet;
- every script reviewed for accuracy, every video passing its checks;
- the long video's chapters and captions match its picture;
- nothing uploaded, scheduled or published.
