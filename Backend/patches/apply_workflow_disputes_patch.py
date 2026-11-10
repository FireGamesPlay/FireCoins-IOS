from __future__ import annotations

import ast
import os
import re
import shutil
import tempfile
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path.cwd()
API = ROOT / "api"
BOT = ROOT.parent / "Bot.py"


def read(path: Path) -> str:
    if not path.is_file():
        raise SystemExit(f"Missing {path}. Run from the webapp directory.")
    return path.read_text(encoding="utf-8")


def atomic_write(path: Path, value: str) -> None:
    fd, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=str(path.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            stream.write(value)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, path.stat().st_mode & 0o777)
        os.replace(temporary, path)
    except Exception:
        try:
            os.unlink(temporary)
        except OSError:
            pass
        raise


def replace_function(source: str, name: str, replacement: str, expected_async: bool = True) -> str:
    tree = ast.parse(source)
    nodes = [
        node for node in tree.body
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name == name
    ]
    if len(nodes) != 1:
        raise SystemExit(f"Expected exactly one top-level function {name!r}; found {len(nodes)}.")
    node = nodes[0]
    if expected_async != isinstance(node, ast.AsyncFunctionDef):
        raise SystemExit(f"Unexpected sync/async declaration for {name!r}.")
    lines = source.splitlines(keepends=True)
    start = node.lineno - 1
    end = node.end_lineno
    replacement = replacement.rstrip() + "\n\n"
    lines[start:end] = [replacement]
    return "".join(lines)


def replace_once(source: str, old: str, new: str, label: str) -> str:
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"Expected one exact match for {label}, found {count}.")
    return source.replace(old, new, 1)


def build_changes() -> dict[Path, str]:
    services_path = API / "services.py"
    server_path = API / "server.py"

    services = read(services_path)
    server = read(server_path)
    bot = read(BOT)

    if "workflow_store" in services or "workflow_store" in bot or 'app.router.add_get("/api/notes"' in server:
        raise SystemExit("Workflow/dispute compatibility patch appears to be partially or fully applied.")

    if not (API / "workflow_store.py").is_file():
        raise SystemExit("Missing api/workflow_store.py. Copy the V2 workflow store module first.")

    services = replace_once(
        services,
        "from .telegram_client import TelegramApiError, telegram\n",
        "from .telegram_client import TelegramApiError, telegram\nfrom .workflow_store import workflow_store\n",
        "services workflow store import",
    )

    proof_reason_pattern = re.compile(
        r'(?m)^(?P<indent>[ \t]*)if action == "remove" and reason in PROOF_REQUIRED_REASONS and not proof_file_id:\r?\n'
        r'(?P=indent)    raise ApiError\("Photo proof is required for this reason\.", status=400, code="proof_required"\)$'
    )
    services, proof_reason_matches = proof_reason_pattern.subn(
        lambda match: (
            f'{match.group("indent")}normalized_reason = "".join(character.casefold() for character in reason if character.isalnum())\n'
            f'{match.group("indent")}proof_required_reason_keys = {{"неубранныетрусы", "оставленныетапки"}}\n'
            f'{match.group("indent")}if action == "remove" and normalized_reason in proof_required_reason_keys and not proof_file_id:\n'
            f'{match.group("indent")}    raise ApiError("Photo proof is required for this reason.", status=400, code="proof_required")'
        ),
        services,
        count=1,
    )
    if proof_reason_matches != 1:
        raise SystemExit(
            "Could not locate exactly one proof-required reason check. "
            "No server files have been changed."
        )

    transaction_dict = '''def _transaction_dict(row: aiosqlite.Row) -> dict[str, Any]:
    amount = int(row["amount"])
    trans_type = str(row["type"])
    signed_amount = amount if trans_type == "add" else -amount
    proof_file_id = row["proof_file_id"] if "proof_file_id" in row.keys() else None
    dispute_value = int(row["disputed"] or 0)
    dispute_status = {
        0: "none",
        1: "pending",
        2: "approved",
        3: "rejected",
    }.get(dispute_value, "none")
    return {
        "id": int(row["id"]),
        "from_id": int(row["from_id"]) if row["from_id"] is not None else None,
        "to_id": int(row["to_id"]) if row["to_id"] is not None else None,
        "type": trans_type,
        "amount": amount,
        "signed_amount": signed_amount,
        "rub_equivalent": amount * 100,
        "reason": str(row["reason"] or ""),
        "timestamp": _timestamp(row["timestamp"]),
        "disputed": dispute_value == 1,
        "dispute_status": dispute_status,
        "has_proof": bool(proof_file_id),
    }'''
    services = replace_function(services, "_transaction_dict", transaction_dict, expected_async=False)

    create_dispute = '''async def create_dispute(user: MiniAppUser, transaction_id_raw: Any) -> dict[str, Any]:
    if user.role != "artemiy":
        raise ApiError("Only Artemiy can dispute transactions.", status=403, code="forbidden")

    transaction_id = _validate_amount(transaction_id_raw)
    async with db_connection() as db:
        await db.execute("BEGIN IMMEDIATE")
        cursor = await db.execute(
            """
            SELECT id
            FROM transactions
            WHERE id = ?
              AND to_id = ?
              AND disputed = 0
              AND id IN (
                  SELECT id
                  FROM transactions
                  WHERE to_id = ?
                  ORDER BY timestamp DESC, id DESC
                  LIMIT 10
              )
            """,
            (transaction_id, ARTEMIY_ID, ARTEMIY_ID),
        )
        row = await cursor.fetchone()
        if not row:
            raise ApiError(
                "Transaction is not eligible for a dispute.",
                status=404,
                code="transaction_unavailable",
            )

        cursor = await db.execute(
            "UPDATE transactions SET disputed = 1 WHERE id = ? AND to_id = ? AND disputed = 0",
            (transaction_id, ARTEMIY_ID),
        )
        if cursor.rowcount != 1:
            raise ApiError("This transaction is already disputed.", status=409, code="already_disputed")
        await db.commit()

    await _notify(
        FATHER_ID,
        f"Артемий оспорил транзакцию #{transaction_id}. Что сделать?",
        reply_markup={
            "inline_keyboard": [
                [
                    {"text": "Принять", "callback_data": f"approve_{transaction_id}"},
                    {"text": "Отклонить", "callback_data": f"reject_{transaction_id}"},
                ]
            ]
        },
    )
    return {"id": transaction_id, "status": "pending"}'''
    services = replace_function(services, "create_dispute", create_dispute, expected_async=True)

    resolve_dispute = '''async def resolve_dispute(user: MiniAppUser, transaction_id_raw: Any, action: str) -> dict[str, Any]:
    if user.role != "father":
        raise ApiError("Only Father can resolve disputes.", status=403, code="forbidden")

    transaction_id = _validate_amount(transaction_id_raw)
    action = str(action or "").lower()
    if action not in {"approve", "reject"}:
        raise ApiError("Dispute action must be approve or reject.", status=400, code="invalid_action")

    async with db_connection() as db:
        await db.execute("BEGIN IMMEDIATE")
        cursor = await db.execute(
            """
            SELECT id, from_id, to_id, type, amount, reason, disputed
            FROM transactions
            WHERE id = ?
            """,
            (transaction_id,),
        )
        row = await cursor.fetchone()
        if not row or int(row["to_id"] or 0) != ARTEMIY_ID:
            raise ApiError("Transaction was not found.", status=404, code="not_found")
        if int(row["disputed"] or 0) != 1:
            raise ApiError("This transaction is not pending dispute.", status=409, code="not_pending")

        if action == "approve":
            amount = int(row["amount"])
            trans_type = str(row["type"])
            original_reason = str(row["reason"] or "")
            await ensure_balance(db, ARTEMIY_ID)

            if trans_type == "remove":
                reversal_type = "add"
                await db.execute(
                    "UPDATE balance SET balance = balance + ? WHERE user_id = ?",
                    (amount, ARTEMIY_ID),
                )
                reversal_reason = f"Возврат по спору: {original_reason}"[:240]
            elif trans_type == "add":
                reversal_type = "remove"
                await db.execute(
                    "UPDATE balance SET balance = balance - ? WHERE user_id = ?",
                    (amount, ARTEMIY_ID),
                )
                reversal_reason = f"Отмена начисления по спору: {original_reason}"[:240]
            else:
                raise ApiError("Unsupported transaction type.", status=409, code="invalid_transaction_type")

            await db.execute(
                """
                INSERT INTO transactions (from_id, to_id, type, amount, reason, disputed)
                VALUES (?, ?, ?, ?, ?, 0)
                """,
                (FATHER_ID, ARTEMIY_ID, reversal_type, amount, reversal_reason),
            )
            await db.execute("UPDATE transactions SET disputed = 2 WHERE id = ?", (transaction_id,))
            status = "approved"
        else:
            await db.execute("UPDATE transactions SET disputed = 3 WHERE id = ?", (transaction_id,))
            status = "rejected"

        await db.commit()

    if action == "approve":
        await _notify(ARTEMIY_ID, f"Папа принял оспаривание транзакции #{transaction_id}. Добавлена обратная операция.")
    else:
        await _notify(ARTEMIY_ID, f"Папа отклонил оспаривание транзакции #{transaction_id}.")

    return {"id": transaction_id, "status": status}'''
    services = replace_function(services, "resolve_dispute", resolve_dispute, expected_async=True)

    casino_status = '''async def get_casino_status(user_id: int) -> dict[str, Any]:
    week_ago_str = (_utc_now() - dt.timedelta(days=7)).strftime("%Y-%m-%d %H:%M:%S")
    async with db_connection() as db:
        cursor = await db.execute(
            """
            SELECT COUNT(*) AS count FROM transactions
            WHERE (from_id = ? OR to_id = ?)
            AND reason IN (?, ?)
            AND timestamp >= ?
            """,
            (user_id, user_id, CASINO_WIN_REASON, CASINO_LOSS_REASON, week_ago_str),
        )
        row = await cursor.fetchone()
        used = int(row["count"]) if row else 0

        cursor = await db.execute(
            """
            SELECT id, amount, reason, timestamp
            FROM transactions
            WHERE (from_id = ? OR to_id = ?)
            AND reason IN (?, ?)
            ORDER BY timestamp DESC
            LIMIT 10
            """,
            (user_id, user_id, CASINO_WIN_REASON, CASINO_LOSS_REASON),
        )
        history_rows = await cursor.fetchall()

    rounds = []
    for item in history_rows:
        reason = str(item["reason"] or "")
        won = reason == CASINO_WIN_REASON
        amount = int(item["amount"])
        rounds.append({
            "id": int(item["id"]),
            "bet": amount // 2 if won else amount,
            "won": won,
            "payout": amount if won else 0,
            "timestamp": _timestamp(item["timestamp"]),
        })

    return {
        "spins_used": used,
        "spins_limit": 2,
        "spins_left": max(0, 2 - used),
        "week_window_start": week_ago_str,
        "win_chance": 0.5,
        "rounds": rounds,
    }'''
    services = replace_function(services, "get_casino_status", casino_status, expected_async=True)

    send_note = '''async def send_note(user: MiniAppUser, text_raw: Any) -> dict[str, Any]:
    note_text = str(text_raw or "").strip()
    if not note_text:
        raise ApiError("Note text is required.", status=400, code="invalid_note")
    if len(note_text) > 1200:
        raise ApiError("Note text is too long.", status=400, code="invalid_note")

    if user.role == "father":
        target_id = ARTEMIY_ID
    elif user.role == "artemiy":
        target_id = FATHER_ID
    else:
        raise ApiError("Access denied.", status=403, code="forbidden")

    try:
        note = await __import__("asyncio").to_thread(
            workflow_store.append_note,
            user.id,
            user.role,
            target_id,
            user.display_name,
            note_text,
        )
    except (OSError, RuntimeError, ValueError) as exc:
        logger.exception("Could not persist Fire Coins note")
        raise ApiError("Note could not be saved to shared history.", status=503, code="note_persistence_failed") from exc

    telegram_notified = True
    try:
        await telegram.send_message(
            chat_id=target_id,
            text=f"Новая заметка:\\n\\n{note_text}\\n\\n— От: {user.display_name}",
        )
    except TelegramApiError:
        telegram_notified = False
        logger.exception("Note saved, but Telegram notification failed")

    return {
        "id": note["id"],
        "sender_id": note["sender_id"],
        "sender_role": note["sender_role"],
        "recipient_id": note["recipient_id"],
        "sender_name": note["sender_name"],
        "text": note["text"],
        "timestamp": dt.datetime.fromtimestamp(note["created_at"], tz=dt.timezone.utc).strftime("%Y-%m-%d %H:%M:%S"),
        "delivered": True,
        "telegram_notified": telegram_notified,
    }


async def get_notes(user: MiniAppUser, limit: int = 100) -> list[dict[str, Any]]:
    if user.role not in {"father", "artemiy"}:
        raise ApiError("Access denied.", status=403, code="forbidden")
    try:
        notes = await __import__("asyncio").to_thread(workflow_store.list_notes, user.id, limit)
    except (OSError, RuntimeError, ValueError) as exc:
        logger.exception("Could not read Fire Coins notes")
        raise ApiError("Notes are temporarily unavailable.", status=503, code="notes_unavailable") from exc
    return [
        {
            "id": str(note.get("id", "")),
            "sender_id": int(note.get("sender_id", 0)),
            "sender_role": str(note.get("sender_role", "")),
            "recipient_id": int(note.get("recipient_id", 0)),
            "sender_name": str(note.get("sender_name", "")),
            "text": str(note.get("text", "")),
            "timestamp": dt.datetime.fromtimestamp(int(note.get("created_at", 0)), tz=dt.timezone.utc).strftime("%Y-%m-%d %H:%M:%S"),
        }
        for note in notes
    ]'''
    services = replace_function(services, "send_note", send_note, expected_async=True)
    services += "\n"

    server = replace_once(
        server,
        "    get_history,\n",
        "    get_history,\n    get_notes,\n",
        "server get_notes import",
    )
    notes_handler = '''async def notes(request: web.Request, user: MiniAppUser) -> web.Response:
    limit_raw = request.query.get("limit", "100")
    try:
        limit = int(limit_raw)
    except ValueError as exc:
        raise ApiError("Limit must be an integer.", status=400, code="invalid_limit") from exc
    return json_response({"ok": True, "items": await get_notes(user, limit=limit)})


'''
    server = replace_once(
        server,
        "async def me(_: web.Request, user: MiniAppUser) -> web.Response:\n",
        notes_handler + "async def me(_: web.Request, user: MiniAppUser) -> web.Response:\n",
        "server notes handler insertion",
    )
    server = replace_once(
        server,
        '    app.router.add_get("/api/me", authed(me))\n',
        '    app.router.add_get("/api/me", authed(me))\n'
        '    app.router.add_get("/api/notes", authed(notes))\n',
        "server notes route",
    )

    bot = replace_once(
        bot,
        "from telegram.error import BadRequest\n",
        "from telegram.error import BadRequest\n\nfrom webapp.api.workflow_store import workflow_store\n",
        "bot workflow store import",
    )

    token_lines = [line for line in bot.splitlines() if line.startswith("TOKEN = os.environ.get(")]
    if len(token_lines) == 1:
        token_line = token_lines[0]
        replacement = 'TOKEN = os.environ.get("TG_BOT_TOKEN", "").strip()\nif not TOKEN:\n    raise RuntimeError("TG_BOT_TOKEN must be configured in the service environment.")'
        bot = bot.replace(token_line, replacement, 1)
    elif 'TOKEN = os.environ.get("TG_BOT_TOKEN", "").strip()' not in bot:
        raise SystemExit("Could not safely locate Telegram token assignment in Bot.py.")

    bot_dispute = '''async def handle_dispute_response(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    await query.answer()
    user_id = query.from_user.id
    if user_id != FATHER_ID:
        await safe_edit_message_text(query, "⛔️ Нет прав на это действие.")
        return

    data = query.data or ""
    if not (data.startswith("approve_") or data.startswith("reject_")):
        return
    action, _, raw_id = data.partition("_")
    try:
        transaction_id = int(raw_id)
    except ValueError:
        await safe_edit_message_text(query, "❌ Некорректный идентификатор транзакции.")
        return

    try:
        async with aiosqlite.connect("points.db") as db:
            await db.execute("BEGIN IMMEDIATE")
            cursor = await db.execute(
                """
                SELECT type, amount, reason, disputed, to_id
                FROM transactions
                WHERE id = ?
                """,
                (transaction_id,),
            )
            row = await cursor.fetchone()
            if not row or int(row[4] or 0) != ARTEMIY_ID:
                await db.rollback()
                await safe_edit_message_text(query, "❌ Транзакция не найдена.")
                return
            trans_type, amount_raw, reason_raw, disputed_raw, _ = row
            if int(disputed_raw or 0) != 1:
                await db.rollback()
                await safe_edit_message_text(query, "❌ Этот спор уже обработан.")
                return

            amount = int(amount_raw)
            if action == "approve":
                await ensure_balance(db, ARTEMIY_ID)
                if trans_type == "remove":
                    reversal_type = "add"
                    await db.execute(
                        "UPDATE balance SET balance = balance + ? WHERE user_id = ?",
                        (amount, ARTEMIY_ID),
                    )
                    reversal_reason = f"Возврат по спору: {reason_raw or ''}"[:240]
                elif trans_type == "add":
                    reversal_type = "remove"
                    await db.execute(
                        "UPDATE balance SET balance = balance - ? WHERE user_id = ?",
                        (amount, ARTEMIY_ID),
                    )
                    reversal_reason = f"Отмена начисления по спору: {reason_raw or ''}"[:240]
                else:
                    await db.rollback()
                    await safe_edit_message_text(query, "❌ Неизвестный тип транзакции.")
                    return

                await db.execute(
                    """
                    INSERT INTO transactions (from_id, to_id, type, amount, reason, disputed)
                    VALUES (?, ?, ?, ?, ?, 0)
                    """,
                    (FATHER_ID, ARTEMIY_ID, reversal_type, amount, reversal_reason),
                )
                await db.execute(
                    "UPDATE transactions SET disputed = 2 WHERE id = ? AND disputed = 1",
                    (transaction_id,),
                )
                result_text = f"✅ Спор #{transaction_id} одобрен. Добавлена обратная операция."
                user_text = f"✅ Папа принял оспаривание транзакции #{transaction_id}. Баланс скорректирован."
            else:
                await db.execute(
                    "UPDATE transactions SET disputed = 3 WHERE id = ? AND disputed = 1",
                    (transaction_id,),
                )
                result_text = f"❌ Оспаривание транзакции #{transaction_id} отклонено."
                user_text = f"❌ Папа отклонил оспаривание транзакции #{transaction_id}."

            await db.commit()

        await safe_edit_message_text(query, result_text)
        await context.bot.send_message(chat_id=ARTEMIY_ID, text=user_text)
    except Exception:
        logger.exception("Ошибка при обработке оспаривания")
        await safe_edit_message_text(query, "⚠️ Ошибка при обработке оспаривания.")'''
    bot = replace_function(bot, "handle_dispute_response", bot_dispute, expected_async=True)

    bot_withdraw = '''async def handle_withdraw_response(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    await query.answer()
    if query.from_user.id != FATHER_ID:
        await safe_edit_message_text(query, "⛔️ Нет прав на это действие.")
        return

    data = query.data or ""
    parts = data.split("_")
    if len(parts) != 3 or parts[0] != "withdraw" or parts[1] not in {"approve", "reject"}:
        await safe_edit_message_text(query, "❌ Некорректные данные запроса.")
        return

    action = parts[1]
    try:
        withdrawal_id = int(parts[2])
        if withdrawal_id <= 0:
            raise ValueError
    except ValueError:
        await safe_edit_message_text(query, "❌ Некорректный идентификатор заявки.")
        return

    try:
        async with aiosqlite.connect("points.db") as db:
            await db.execute("BEGIN IMMEDIATE")
            cursor = await db.execute(
                "SELECT user_id, amount, status FROM withdrawals WHERE id = ?",
                (withdrawal_id,),
            )
            row = await cursor.fetchone()
            if not row or str(row[2]) != "pending":
                await db.rollback()
                await safe_edit_message_text(query, "❌ Заявка уже обработана или не найдена.")
                return

            target_user_id = int(row[0])
            amount = int(row[1])
            if action == "approve":
                await ensure_balance(db, target_user_id)
                cursor = await db.execute(
                    "SELECT balance FROM balance WHERE user_id = ?",
                    (target_user_id,),
                )
                balance_row = await cursor.fetchone()
                if not balance_row or int(balance_row[0]) < amount:
                    await db.rollback()
                    await safe_edit_message_text(query, f"❌ Недостаточно баллов для вывода ({amount}).")
                    return

                cursor = await db.execute(
                    "UPDATE withdrawals SET status = 'approved' WHERE id = ? AND status = 'pending'",
                    (withdrawal_id,),
                )
                if cursor.rowcount != 1:
                    await db.rollback()
                    await safe_edit_message_text(query, "❌ Заявка уже обработана.")
                    return

                await db.execute(
                    "UPDATE balance SET balance = balance - ? WHERE user_id = ?",
                    (amount, target_user_id),
                )
                await db.execute(
                    "INSERT INTO transactions (from_id, to_id, type, amount, reason) VALUES (?, ?, ?, ?, ?)",
                    (target_user_id, target_user_id, "remove", amount, WITHDRAWAL_REASON),
                )
                status_text = f"✅ Запрос вывода #{withdrawal_id} одобрен. Баллы списаны."
                user_text = f"✅ Ваш запрос на вывод {amount} баллов одобрен и баллы списаны."
            else:
                cursor = await db.execute(
                    "UPDATE withdrawals SET status = 'rejected' WHERE id = ? AND status = 'pending'",
                    (withdrawal_id,),
                )
                if cursor.rowcount != 1:
                    await db.rollback()
                    await safe_edit_message_text(query, "❌ Заявка уже обработана.")
                    return
                status_text = f"❌ Запрос вывода #{withdrawal_id} отклонён."
                user_text = f"❌ Ваш запрос на вывод {amount} баллов отклонён."

            await db.commit()

        await safe_edit_message_text(query, status_text)
        await context.bot.send_message(chat_id=target_user_id, text=user_text)
    except Exception:
        logger.exception("Ошибка при обработке запроса на вывод")
        await safe_edit_message_text(query, "⚠️ Ошибка при обработке запроса.")'''
    bot = replace_function(bot, "handle_withdraw_response", bot_withdraw, expected_async=True)
    bot_dispute_start = '''async def dispute_callback_handler(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    await query.answer()
    if query.from_user.id != ARTEMIY_ID:
        await safe_edit_message_text(query, "⛔️ Нет прав на это действие.")
        return

    data = query.data or ""
    if not data.startswith("dispute_"):
        await query.answer("Некорректные данные.", show_alert=True)
        return

    try:
        transaction_id = int(data.split("_", 1)[1])
    except (TypeError, ValueError):
        await safe_edit_message_text(query, "❌ Некорректный идентификатор транзакции.")
        return

    try:
        async with aiosqlite.connect("points.db") as db:
            await db.execute("BEGIN IMMEDIATE")
            cursor = await db.execute(
                """
                SELECT id
                FROM transactions
                WHERE id = ?
                  AND to_id = ?
                  AND disputed = 0
                  AND id IN (
                      SELECT id
                      FROM transactions
                      WHERE to_id = ?
                      ORDER BY timestamp DESC, id DESC
                      LIMIT 10
                  )
                """,
                (transaction_id, ARTEMIY_ID, ARTEMIY_ID),
            )
            row = await cursor.fetchone()
            if not row:
                await db.rollback()
                await safe_edit_message_text(query, "❌ Эту транзакцию уже нельзя оспорить.")
                return

            cursor = await db.execute(
                "UPDATE transactions SET disputed = 1 WHERE id = ? AND to_id = ? AND disputed = 0",
                (transaction_id, ARTEMIY_ID),
            )
            if cursor.rowcount != 1:
                await db.rollback()
                await safe_edit_message_text(query, "❌ Эта транзакция уже оспаривается.")
                return
            await db.commit()
    except Exception:
        logger.exception("Ошибка при установке disputed")
        await safe_edit_message_text(query, "⚠️ Ошибка при отправке запроса на оспаривание.")
        return

    await safe_edit_message_text(query, f"⚠️ Запрос на оспаривание транзакции #{transaction_id} отправлен отцу.")
    try:
        await context.bot.send_message(
            chat_id=FATHER_ID,
            text=f"⚠️ Артемий оспорил транзакцию #{transaction_id}. Что сделать?",
            reply_markup=InlineKeyboardMarkup([
                [
                    InlineKeyboardButton("✅ Принять", callback_data=f"approve_{transaction_id}"),
                    InlineKeyboardButton("❌ Отклонить", callback_data=f"reject_{transaction_id}"),
                ]
            ]),
        )
    except Exception:
        logger.exception("Не удалось отправить сообщение отцу об оспаривании")'''
    bot = replace_function(bot, "dispute_callback_handler", bot_dispute_start, expected_async=True)
    bot = replace_once(
        bot,
        '            dispute = " ⚠️" if row[5] else ""\n',
        '            dispute_value = int(row[5] or 0)\n'
        '            dispute = {1: " ⚠️", 2: " ✅", 3: " ❌"}.get(dispute_value, "")\n',
        "bot history dispute status display",
    )

    bot_note = '''async def leave_note_text(update: Update, context: ContextTypes.DEFAULT_TYPE):
    text: Optional[str] = update.effective_message.text or ""
    if text == "🔙 Назад":
        role = get_role(update.effective_user.id)
        await update.effective_message.reply_text("Отмена. Возвращаю в меню.", reply_markup=get_main_menu(role))
        return ConversationHandler.END

    note_text = text.strip()
    if not note_text:
        await update.effective_message.reply_text("⛔️ Заметка не должна быть пустой.")
        return LEAVE_NOTE_TEXT
    if len(note_text) > 1200:
        await update.effective_message.reply_text("⛔️ Максимальная длина заметки — 1200 символов.")
        return LEAVE_NOTE_TEXT

    sender_id = update.effective_user.id
    role = get_role(sender_id)
    if role == "father":
        target_id = ARTEMIY_ID
    elif role == "artemiy":
        target_id = FATHER_ID
    else:
        await update.effective_message.reply_text("⛔️ Нельзя отправлять заметки.", reply_markup=get_main_menu(role))
        return ConversationHandler.END

    sender_name = update.effective_user.first_name or ("Папа" if role == "father" else "Артемий")
    try:
        workflow_store.append_note(sender_id, role, target_id, sender_name, note_text)
    except Exception:
        logger.exception("Не удалось сохранить заметку")
        await update.effective_message.reply_text("⚠️ Не удалось сохранить заметку.", reply_markup=get_main_menu(role))
        return ConversationHandler.END

    try:
        await context.bot.send_message(
            chat_id=target_id,
            text=f"📝 Новая заметка:\\n\\n{note_text}\\n\\n— От: {sender_name}",
        )
    except Exception:
        logger.exception("Заметка сохранена, но Telegram-уведомление не доставлено")
        await update.effective_message.reply_text(
            "✅ Заметка сохранена. Telegram-уведомление не доставлено.",
            reply_markup=get_main_menu(role),
        )
        return ConversationHandler.END

    await update.effective_message.reply_text("✅ Заметка отправлена.", reply_markup=get_main_menu(role))
    return ConversationHandler.END'''
    bot = replace_function(bot, "leave_note_text", bot_note, expected_async=True)

    # Validate the three edited Python modules before touching any live file.
    ast.parse(services)
    ast.parse(server)
    ast.parse(bot)
    return {
        services_path: services,
        server_path: server,
        BOT: bot,
    }


def main() -> None:
    if Path.cwd().resolve() != API.parent.resolve():
        raise SystemExit("Run from /root/ScoringSystemBot/webapp, not another directory.")
    changes = build_changes()

    backup_root = ROOT.parent / ".backups"
    backup_root.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(backup_root, 0o700)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    backup_dir = backup_root / f"firecoins-workflow-disputes-{stamp}"
    backup_dir.mkdir(mode=0o700)

    for path in changes:
        relative = path.relative_to(ROOT.parent)
        backup_path = backup_dir / relative
        backup_path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        shutil.copy2(path, backup_path)

    try:
        for path, content in changes.items():
            atomic_write(path, content)
    except Exception:
        for path in changes:
            relative = path.relative_to(ROOT.parent)
            shutil.copy2(backup_dir / relative, path)
        raise

    print("Workflow/dispute compatibility patch applied.")
    print(f"Backups: {backup_dir}")
    print("Database schema and points.db were not touched.")
    print("No service was restarted.")


if __name__ == "__main__":
    main()
