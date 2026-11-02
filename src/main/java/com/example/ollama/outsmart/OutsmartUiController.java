package com.example.ollama.outsmart;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Serves the Outsmart setup and game pages, with small HTML updates for HTMX requests. */
@Controller
@RequestMapping("/outsmart")
public class OutsmartUiController {

    private static final String PAGE = "outsmart/index";
    private static final String CONTENT = PAGE + " :: content";
    private static final String DEFAULT_MODEL = "gemma3:1b";

    private final OutsmartGameRegistry games;
    private final OutsmartGameRunner runner;

    /** Uses the same game registry and runner as the Outsmart JSON API. */
    public OutsmartUiController(OutsmartGameRegistry games, OutsmartGameRunner runner) {
        this.games = games;
        this.runner = runner;
    }

    /** Shows the setup form for a new in-memory game. */
    @GetMapping
    public String setup(Model model) {
        model.addAttribute("rows", defaultRows());
        return PAGE;
    }

    /** Shows public progress for an existing game, or a useful message if it has gone away. */
    @GetMapping("/games/{gameId}")
    public String game(@PathVariable UUID gameId, Model model, HttpServletResponse response) {
        try {
            model.addAttribute("game", OutsmartGameResponse.from(games.get(gameId)));
        } catch (NoSuchElementException exception) {
            model.addAttribute("rows", defaultRows());
            model.addAttribute("error", "Game not found. It may have been deleted or the app restarted.");
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        }
        return PAGE;
    }

    /** Validates form rows, creates a game, and opens it without a full reload for HTMX. */
    @PostMapping("/games")
    public String create(@RequestParam MultiValueMap<String, String> fields,
            @RequestHeader(value = "HX-Request", defaultValue = "false") boolean htmx,
            Model model, HttpServletResponse response) {
        List<String> names = fields.getOrDefault("name", List.of());
        List<String> modelIds = fields.getOrDefault("model", List.of());
        try {
            OutsmartGame created = games.create(participants(names, modelIds));
            if (htmx) {
                response.setHeader("HX-Push-Url", "/outsmart/games/" + created.id());
                model.addAttribute("game", OutsmartGameResponse.from(created));
                return CONTENT;
            }
            return "redirect:/outsmart/games/" + created.id();
        } catch (IllegalArgumentException | IllegalStateException exception) {
            model.addAttribute("rows", rows(names, modelIds));
            model.addAttribute("extraOpen", names.stream().skip(3).anyMatch(name -> !name.isBlank()));
            model.addAttribute("error", exception.getMessage());
            // HTMX swaps successful HTML responses. Use a normal 200 for an editable form error;
            // a direct form submission can still report the corresponding HTTP client error.
            response.setStatus(htmx ? HttpServletResponse.SC_OK : HttpServletResponse.SC_BAD_REQUEST);
            return view(htmx);
        }
    }

    /** Runs one turn and redraws the public game panel after all local models reply. */
    @PostMapping("/games/{gameId}/turns")
    public String playTurn(@PathVariable UUID gameId,
            @RequestHeader(value = "HX-Request", defaultValue = "false") boolean htmx,
            Model model, HttpServletResponse response) {
        try {
            OutsmartGame updated = runner.runNextTurn(gameId);
            if (htmx) {
                model.addAttribute("game", OutsmartGameResponse.from(updated));
                return CONTENT;
            }
            return "redirect:/outsmart/games/" + gameId;
        } catch (NoSuchElementException exception) {
            model.addAttribute("rows", defaultRows());
            model.addAttribute("error", "Game not found. It may have been deleted or the app restarted.");
            response.setStatus(htmx ? HttpServletResponse.SC_OK : HttpServletResponse.SC_NOT_FOUND);
            return view(htmx);
        } catch (IllegalStateException exception) {
            // A simultaneous request may have completed the turn first. Reload the current
            // snapshot so the page never presents stale balances or another player's secrets.
            model.addAttribute("game", OutsmartGameResponse.from(games.get(gameId)));
            model.addAttribute("error", exception.getMessage());
            response.setStatus(htmx ? HttpServletResponse.SC_OK : HttpServletResponse.SC_CONFLICT);
            return view(htmx);
        }
    }

    /** Removes a game and returns to the setup form, releasing its in-memory slot. */
    @PostMapping("/games/{gameId}/delete")
    public String delete(@PathVariable UUID gameId,
            @RequestHeader(value = "HX-Request", defaultValue = "false") boolean htmx,
            Model model, HttpServletResponse response) {
        games.remove(gameId);
        if (htmx) {
            response.setHeader("HX-Push-Url", "/outsmart");
            model.addAttribute("rows", defaultRows());
            return CONTENT;
        }
        return "redirect:/outsmart";
    }

    /** Converts the eight form rows into the 3–8 named Ollama participants for a new game. */
    private List<OutsmartGame.Participant> participants(List<String> names, List<String> modelIds) {
        if (names.size() != 8 || modelIds.size() != 8) {
            throw new IllegalArgumentException("Submit all eight player rows; leave unused names blank.");
        }
        List<OutsmartGame.Participant> participants = new ArrayList<>();
        for (int index = 0; index < names.size(); index++) {
            String name = names.get(index).strip();
            String modelId = modelIds.get(index).strip();
            if (name.isEmpty()) {
                if (index < 3) {
                    throw new IllegalArgumentException("The first three player names are required.");
                }
                continue;
            }
            if (name.length() > 80 || modelId.isEmpty() || modelId.length() > 160) {
                throw new IllegalArgumentException("Use names up to 80 characters and model IDs up to 160 characters.");
            }
            participants.add(new OutsmartGame.Participant(name, new OutsmartGame.Model("ollama", modelId)));
        }
        return participants;
    }

    /** Supplies the initial three named rows and five optional rows for the setup page. */
    private List<FormRow> defaultRows() {
        return rows(List.of("Alex", "Blake", "Charlie", "", "", "", "", ""),
                List.of(DEFAULT_MODEL, DEFAULT_MODEL, DEFAULT_MODEL, DEFAULT_MODEL,
                        DEFAULT_MODEL, DEFAULT_MODEL, DEFAULT_MODEL, DEFAULT_MODEL));
    }

    /** Preserves submitted text when the setup form needs to show a validation error. */
    private List<FormRow> rows(List<String> names, List<String> modelIds) {
        List<FormRow> rows = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            rows.add(new FormRow(index + 1,
                    index < names.size() ? names.get(index) : "",
                    index < modelIds.size() ? modelIds.get(index) : DEFAULT_MODEL,
                    index < 3));
        }
        return rows;
    }

    /** Chooses the whole page for standard forms and just the replaceable panel for HTMX. */
    private String view(boolean htmx) {
        return htmx ? CONTENT : PAGE;
    }

    /** One editable player row; optional rows let the user choose up to eight participants. */
    public record FormRow(int number, String name, String modelId, boolean required) {
    }
}
