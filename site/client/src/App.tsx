/**
 * Planta de Mineração: experiência pública e documental, com rotas técnicas
 * que preservam a identidade editorial e tornam o contrato navegável.
 */
import { Toaster } from "@/components/ui/sonner";
import { TooltipProvider } from "@/components/ui/tooltip";
import NotFound from "@/pages/NotFound";
import { Route, Router as WouterRouter, Switch } from "wouter";
import ErrorBoundary from "./components/ErrorBoundary";
import { ThemeProvider } from "./contexts/ThemeContext";
import ApiCatalog from "./pages/ApiCatalog";
import Compatibility from "./pages/Compatibility";
import DocsOverview from "./pages/DocsOverview";
import GettingStarted from "./pages/GettingStarted";
import Home from "./pages/Home";
import ManifestReference from "./pages/ManifestReference";
import Progression from "./pages/Progression";
import TutorialPage from "./pages/TutorialPage";
import Tutorials from "./pages/Tutorials";

function Router() {
  return (
    <Switch>
      <Route path="/" component={Home} />
      <Route path="/docs" component={DocsOverview} />
      <Route path="/docs/primeiros-passos" component={GettingStarted} />
      <Route path="/docs/progredir" component={Progression} />
      <Route path="/docs/apis" component={ApiCatalog} />
      <Route path="/docs/manifesto" component={ManifestReference} />
      <Route path="/docs/compatibilidade" component={Compatibility} />
      <Route path="/docs/tutoriais" component={Tutorials} />
      <Route path="/docs/tutoriais/:id" component={TutorialPage} />
      <Route path="/404" component={NotFound} />
      <Route component={NotFound} />
    </Switch>
  );
}

export default function App() {
  const base = import.meta.env.BASE_URL === "/" ? "/" : import.meta.env.BASE_URL.replace(/\/$/, "");
  return (
    <ErrorBoundary>
      <ThemeProvider defaultTheme="dark">
        <TooltipProvider>
          <Toaster />
          <WouterRouter base={base}><Router /></WouterRouter>
        </TooltipProvider>
      </ThemeProvider>
    </ErrorBoundary>
  );
}
