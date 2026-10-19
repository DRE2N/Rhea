export function parseCommentedJson(source) {
  // Match quoted strings first, so a URL or // inside a label stays intact.
  const withoutComments = source.replace(/"(?:\\.|[^"\\])*"|\/\/[^\r\n]*|\/\*[\s\S]*?\*\//g,
    match => match.startsWith('"') ? match : match.replace(/[^\r\n]/g, ' '));
  return JSON.parse(withoutComments);
}
