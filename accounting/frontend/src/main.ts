import { bootstrapApplication } from "@angular/platform-browser";
import { provideHttpClient } from "@angular/common/http";
import { provideRouter } from "@angular/router";
import { AppComponent } from "./app";
import { WORKSPACE_ROUTES } from "./workspace-routes";
bootstrapApplication(AppComponent, { providers: [provideHttpClient(), provideRouter(WORKSPACE_ROUTES)] }).catch(
  console.error,
);
