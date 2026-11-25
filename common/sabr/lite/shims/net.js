export const isIP = (s) => /^\d+\.\d+\.\d+\.\d+$/.test(s) ? 4 : /:/.test(s) ? 6 : 0; export default { isIP };
